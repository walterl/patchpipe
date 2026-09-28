# Agent rules for Patchpipe

## Target platform

- HARD RULE: Google Play is OFF LIMITS. NEVER add anything to support it: no Play Services, Firebase
  (incl. FCM), Play Integrity, Play Billing, Play in-app updates, Play Console metadata, or code and
  manifest entries whose only purpose is Play review or Play policy compliance.
- The primary target is GrapheneOS devices WITHOUT ANY Play Services (or stand-in such as microG)
  installed. Only rely on AOSP platform APIs and AndroidX libraries that work without them.
