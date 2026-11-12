"""Rule engine: classifies a failed test and produces a 2-3 line fix suggestion for developers
plus a plain-English sentence for non-technical readers.

Product defects (wrong behaviour of the endpoint under test) stay separate from
environment / setup blockers (the intended check never ran). Reports must use the
actual cause — never treat a login 429, missing token or fixture crash as a
'valid request rejected' product bug.
"""
from __future__ import annotations

CATEGORIES: dict[str, dict] = {
    "AUTH_BYPASS": {
        "title": "Authentication not enforced",
        "severity": "Critical",
        "plain": "The system allowed access without a valid login.",
        "fix": ("{endpoint} returned HTTP {actual} for a request without valid credentials (expected {expected}). "
                "Enable the authentication guard/middleware on this route so missing, invalid, malformed and expired "
                "tokens are rejected with 401 before any business logic runs."),
    },
    "AUTHZ_BYPASS": {
        "title": "Authorisation / role check missing",
        "severity": "Critical",
        "plain": "A user was able to do something their role should not allow.",
        "fix": ("{endpoint} returned HTTP {actual} to a user without the required permission (expected {expected}). "
                "Add a role/ownership check after authentication and return 403 when the caller lacks the permission "
                "or does not own the resource."),
    },
    "WRONG_AUTH_STATUS": {
        "title": "Wrong authentication error code",
        "severity": "Medium",
        "plain": "The system blocked the request correctly but reported the wrong kind of error.",
        "fix": ("{endpoint} returned HTTP {actual} instead of {expected}. Use 401 when credentials are missing or "
                "invalid and 403 when the caller is authenticated but not allowed, so clients can react correctly."),
    },
    "METHOD_NOT_BLOCKED": {
        "title": "Unsupported HTTP method accepted",
        "severity": "Medium",
        "plain": "The system accepted a type of request it should refuse.",
        "fix": ("{endpoint} answered HTTP {actual} to an unsupported method (expected {expected}). Restrict the route "
                "to its documented methods and return 405 with an 'Allow' header listing the supported ones."),
    },
    "RATE_LIMIT_MISSING": {
        "title": "Rate limiting not enforced",
        "severity": "High",
        "plain": "The system does not slow down excessive requests, which leaves it open to abuse.",
        "fix": ("A burst of requests to {endpoint} never received HTTP 429 (last status {actual}). Configure rate "
                "limiting at the gateway or service for this route and return 429 with a 'Retry-After' header."),
    },
    "VALIDATION_MISSING": {
        "title": "Input validation missing",
        "severity": "High",
        "plain": "The system accepted invalid or incomplete information.",
        "fix": ("{endpoint} accepted an invalid payload with HTTP {actual} (expected {expected}). Validate required "
                "fields, types, formats and ranges at the controller/DTO layer and return 400 with a field-level "
                "error message before persisting anything."),
    },
    "WRONG_VALIDATION_STATUS": {
        "title": "Wrong validation error code",
        "severity": "Low",
        "plain": "The system rejected bad information but reported it with an unexpected error type.",
        "fix": ("{endpoint} rejected invalid input with HTTP {actual} instead of the documented {expected}. Align the "
                "error mapping with the API contract (or update the contract) so clients get a consistent code."),
    },
    "SERVER_ERROR": {
        "title": "Unhandled server error",
        "severity": "Critical",
        "plain": "The system crashed with an internal error instead of answering properly.",
        "fix": ("{endpoint} returned HTTP {actual} (expected {expected}). Check the service logs for the stack trace "
                "at the run time shown, add input/null handling around the failing code path, and map known "
                "business errors to 4xx responses instead of letting exceptions surface as 5xx."),
    },
    "UNEXPECTED_REJECTION": {
        "title": "Valid request rejected",
        "severity": "High",
        "plain": "A normal, valid request was refused.",
        "fix": ("{endpoint} rejected a valid, documented request with HTTP {actual} (expected {expected}). Compare the "
                "request in the evidence with the contract; check recent changes to validation rules, required "
                "headers, test data state and permissions for this user."),
    },
    "NOT_FOUND_HANDLING": {
        "title": "Missing resource not handled",
        "severity": "Medium",
        "plain": "Asking for something that does not exist gave a confusing answer.",
        "fix": ("{endpoint} returned HTTP {actual} for a non-existent resource (expected {expected}). Look the "
                "resource up first and return 404 with a clear message when it does not exist."),
    },
    "STATUS_MISMATCH": {
        "title": "Unexpected status code",
        "severity": "Medium",
        "plain": "The system answered differently from what is documented.",
        "fix": ("{endpoint} returned HTTP {actual} but the documented result is {expected}. Confirm which behaviour "
                "is correct with the API owner and fix either the implementation or the Confluence contract."),
    },
    "SCHEMA_MISMATCH": {
        "title": "Response does not match contract",
        "severity": "Medium",
        "plain": "The system's answer was missing information or was in the wrong format.",
        "fix": ("{endpoint} returned a body that breaks the documented contract: {detail}. Update the response "
                "serializer/DTO so field names, types and required fields match the Confluence specification."),
    },
    "SLOW_RESPONSE": {
        "title": "Response time above limit",
        "severity": "Medium",
        "plain": "The system answered, but too slowly.",
        "fix": ("{endpoint} exceeded the response-time limit: {detail}. Profile the handler (database queries, "
                "downstream calls, missing indexes or caching) and re-test under the same conditions."),
    },
    "DATA_LEAK": {
        "title": "Sensitive data exposed",
        "severity": "Critical",
        "plain": "The system revealed sensitive information it should keep private.",
        "fix": ("{endpoint} exposes sensitive data: {detail}. Remove these fields from the response model and "
                "review logging/serialization so secrets and personal data never leave the service."),
    },
    "CONNECTIVITY": {
        "title": "Service unreachable",
        "severity": "High",
        "plain": "The system could not be reached during testing.",
        "fix": ("The request to {endpoint} failed before a response arrived: {detail}. Check that the service is "
                "deployed and healthy, the base URL/DNS/VPN/firewall are correct for this environment, and the "
                "timeout is appropriate. This is an environment blocker, not a product defect — do not file it as a bug."),
    },
    "RATE_LIMITED": {
        "title": "Rate limited — check could not run",
        "severity": "High",
        "plain": "The check could not run because the environment refused further attempts (too many requests).",
        "fix": ("The request used to set up or run this check was rejected with HTTP 429 ({detail}). Wait for the "
                "rate-limit window (or use dedicated test credentials / a higher limit on the test environment), "
                "then re-run. This is an environment blocker, not a product defect on {endpoint} — do not file it as a bug."),
    },
    "SETUP_BLOCKED": {
        "title": "Setup / login blocked — check could not run",
        "severity": "High",
        "plain": "The check never started because login or test setup failed.",
        "fix": ("The test for {endpoint} stopped during setup/login: {detail}. Restore a valid token or credentials, "
                "fix the fixture, then re-run. This is a setup blocker, not a product defect on the endpoint under "
                "test — do not file it as a bug."),
    },
    "NOT_AUTOMATED": {
        "title": "Not automated — check could not run",
        "severity": "High",
        "plain": "This check was not automated or was not included in the run, so it could not run.",
        "fix": ("Case was not executed: {detail}. Automate it when selectors/fixtures are ready, or run it manually "
                "and record the result. This is not a product defect — do not file it as a bug."),
    },
    "TEST_ERROR": {
        "title": "Test could not complete",
        "severity": "Medium",
        "plain": "The check itself hit an unexpected problem and needs QA review.",
        "fix": ("The test for {endpoint} stopped with an unexpected error: {detail}. QA should review the test data "
                "and setup; if the API response shape changed, raise it with the API owner."),
    },
    "ASSERTION": {
        "title": "Business rule check failed",
        "severity": "Medium",
        "plain": "A result did not match the documented business rule.",
        "fix": ("A business-rule check on {endpoint} failed: {detail}. Compare the response in the evidence with "
                "the documented rule and correct the implementation or the documentation."),
    },
}

# These are not product defects. They must not drive "Go with conditions" or appear as Jira bugs.
BLOCKER_CATEGORIES = frozenset({"CONNECTIVITY", "RATE_LIMITED", "SETUP_BLOCKED", "NOT_AUTOMATED"})

SEVERITY_ORDER = ["Critical", "High", "Medium", "Low"]

# Only fixture / login frames. Do not use names that appear as test-call arguments
# (e.g. authed_api=api) — those sit in every traceback and would hide real defects.
_SETUP_MARKERS = (
    "conftest.py", "get_token(", "_login_session", "auth.get_token",
    "tests/auth.py",
)
_RATE_LIMIT_TEXT = (
    "too many attempts", "too many requests", "rate limit", "retry-after",
    "please wait a minute", "throttl",
)
_TOKEN_MISSING_TEXT = (
    "missing token", "no token provided", "token is missing",
    "missing api_username", "missing api_password", "missing credentials",
)
_CONNECTIVITY_TEXT = (
    "connectionerror", "connecttimeout", "readtimeout", "connection aborted",
    "failed to establish", "nameresolutionerror", "max retries exceeded",
    "connection refused", "network is unreachable",
)


def _fmt(codes) -> str:
    return " or ".join(str(c) for c in codes) if codes else "the documented status"


def _blob(test: dict) -> str:
    parts = [test.get("error") or "", test.get("traceback") or "", test.get("skip_reason") or ""]
    for call in test.get("calls") or []:
        parts.append(str(call.get("error") or ""))
        parts.append(str(call.get("reason") or ""))
        parts.append(str(call.get("response_body") or "")[:400])
    return " ".join(parts).lower()


def _expected(test: dict) -> list:
    return list(test.get("error_details", {}).get("expected") or test.get("expected_status") or [])


def _actual(test: dict):
    details = test.get("error_details") or {}
    if details.get("actual") is not None:
        return details["actual"]
    calls = test.get("calls") or []
    if calls and calls[-1].get("status") is not None:
        return calls[-1]["status"]
    return None


def _expected_429(test: dict) -> bool:
    return 429 in _expected(test)


def is_rate_limited(test: dict) -> bool:
    """True when the check was refused by a rate limiter and 429 was not the expected result."""
    if _expected_429(test):
        return False
    if _actual(test) == 429:
        return True
    blob = _blob(test)
    return any(token in blob for token in _RATE_LIMIT_TEXT)


def is_setup_block(test: dict) -> bool:
    """True when the intended check never ran because login, a fixture or credentials failed."""
    blob = _blob(test)
    if any(token in blob for token in _TOKEN_MISSING_TEXT):
        return True
    tb = (test.get("traceback") or "").lower()
    if any(marker in tb for marker in _SETUP_MARKERS):
        return True
    if "in token\n" in tb or tb.rstrip().endswith("in token"):
        return True
    if test.get("status") == "broken" and "conftest.py" in tb:
        return True
    return False


def is_connectivity(test: dict) -> bool:
    if test.get("error_kind") == "connectivity":
        return True
    return any(token in _blob(test) for token in _CONNECTIVITY_TEXT)


def is_blocker_category(category: str) -> bool:
    return category in BLOCKER_CATEGORIES


def classify(test: dict) -> str:
    kind = test.get("error_kind")
    if kind == "not_automated":
        return "NOT_AUTOMATED"
    if kind == "connectivity" or (kind not in ("schema", "slow", "leak") and is_connectivity(test)):
        return "CONNECTIVITY"
    if kind == "schema":
        return "SCHEMA_MISMATCH"
    if kind == "slow":
        return "SLOW_RESPONSE"
    if kind == "leak":
        return "DATA_LEAK"

    # Environment / setup first — these are not product status mismatches.
    if is_rate_limited(test):
        return "RATE_LIMITED"
    if is_setup_block(test):
        return "SETUP_BLOCKED"

    if kind == "test_error" or (test.get("status") == "broken" and kind not in ("status", "assertion")):
        return "TEST_ERROR"
    if kind != "status":
        return "ASSERTION"

    expected = _expected(test)
    actual = _actual(test)
    if actual is None:
        return "STATUS_MISMATCH"
    ok = 200 <= actual < 300
    if actual >= 500 and not any(e >= 500 for e in expected):
        return "SERVER_ERROR"
    if 401 in expected:
        return "AUTH_BYPASS" if ok else ("WRONG_AUTH_STATUS" if actual == 403 else "STATUS_MISMATCH")
    if 403 in expected:
        return "AUTHZ_BYPASS" if ok else ("WRONG_AUTH_STATUS" if actual == 401 else "STATUS_MISMATCH")
    if 405 in expected:
        return "METHOD_NOT_BLOCKED"
    if 429 in expected:
        return "RATE_LIMIT_MISSING"
    if any(e in (400, 422) for e in expected):
        return "VALIDATION_MISSING" if ok else "WRONG_VALIDATION_STATUS"
    if 404 in expected:
        return "NOT_FOUND_HANDLING"
    if expected and all(200 <= e < 300 for e in expected) and 400 <= actual < 500:
        return "UNEXPECTED_REJECTION"
    return "STATUS_MISMATCH"


def suggest(test: dict) -> dict:
    """Returns category key, title, severity, dev fix and plain-English text for a failed/broken test."""
    key = classify(test)
    rule = CATEGORIES[key]
    details = test.get("error_details", {})
    expected = details.get("expected") or test.get("expected_status") or []
    detail = (test.get("error") or "").split(": ", 1)[-1][:220]
    text = rule["fix"].format(endpoint=test.get("endpoint", "the endpoint"), expected=_fmt(expected),
                              actual=details.get("actual", _actual(test) or "n/a"), detail=detail)
    severity = rule["severity"]
    if key in ("STATUS_MISMATCH", "ASSERTION", "SCHEMA_MISMATCH") and test.get("priority") == "High":
        severity = "High"
    return {
        "category": key,
        "title": rule["title"],
        "severity": severity,
        "fix": text,
        "plain": rule["plain"],
        "blocker": key in BLOCKER_CATEGORIES,
    }
