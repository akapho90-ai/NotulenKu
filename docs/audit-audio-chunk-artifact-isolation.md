# Audio chunk artifact isolation

The chunk preparation path now fails closed if a session chunk directory already contains files. This prevents stale chunk reuse when the same session ID is reused or a previous extraction left artifacts. It also refuses pre-existing planned output files, removes the session directory on extraction failure, and verifies that the count of prepared chunks matches the plan.

This is a safety guard, not a transactional staging system. Interrupted extraction can still leave an empty or partially populated directory; subsequent attempts will refuse to use it until cleanup is performed. The original audio source is never deleted by this flow.

Source review only; CI/device validation pending.
