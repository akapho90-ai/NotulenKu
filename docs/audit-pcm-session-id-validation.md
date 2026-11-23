# PCM writer session ID validation

PCM writer filenames previously used a sanitized ID, allowing distinct inputs to collapse to the same sanitized name. This now rejects IDs outside the restricted 1–128 character [A-Za-z0-9_-] set instead of rewriting them. This prevents filename aliasing from sanitization.