## Security Issue Type
<!-- Check one -->
- [ ] Vulnerability Report
- [ ] Security Enhancement Request
- [ ] Security Documentation Improvement

---

## ⚠️ IMPORTANT: Private Disclosure
<!-- If reporting a vulnerability that is not already publicly known: -->
<!-- DO NOT create a public issue. Instead, set the type to incident: -->
<!-- 1. Email: security@booklore.org (if available) -->
<!-- 2. GitHub Security Advisory: https://github.com/grimmory-tools/grimmory/security/advisories/new -->
<!-- 3. Discord DM to @maintainer -->


<!-- Only proceed with this public template for: -->
<!-- - Security hardening suggestions -->
<!-- - Already-public vulnerabilities in dependencies -->
<!-- - Security documentation improvements -->

---

## Description
<!-- Clear description of the security concern -->

## Severity Assessment
<!-- Your assessment of impact -->
- [ ] Critical (Remote Code Execution, Auth Bypass)
- [ ] High (Data Exposure, Privilege Escalation)
- [ ] Medium (XSS, CSRF, Information Disclosure)
- [ ] Low (Security Hardening, Best Practice)

## Affected Component
<!-- Check all that apply -->
- [ ] Authentication/Authorization
- [ ] API Endpoints
- [ ] File Upload/Processing
- [ ] Database Access
- [ ] OPDS/Kobo Sync
- [ ] Docker/Deployment Config
- [ ] Dependency
- [ ] Other: <!-- specify -->

## Impact
<!-- What could an attacker do? What data/systems are at risk? -->

## Steps to Reproduce (if applicable)
<!-- For already-public issues or hardening suggestions -->
1. 
2. 
3. 

## Affected Versions
<!-- Which versions are affected? -->

## Mitigation/Workaround
<!-- Is there a temporary workaround users can apply? -->

## Suggested Fix
<!-- Optional: How should this be addressed? -->

## References
<!-- CVE numbers, security advisories, similar issues in other projects, etc. -->

---
/label ~"type::security" ~"prio::p0-critical" ~"status::needs-triage"
