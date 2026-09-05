# Bug report: expired links still count as clicks

Users are reporting that analytics for a short link keep increasing even after the link
has expired and stopped resolving.

Steps to reproduce:
1. Create a short URL with an expiry time in the near future.
2. Wait for it to expire.
3. Follow the short link. It correctly returns an expired response.
4. Check the link's click stats. The click count went up anyway, even though the
   redirect did not happen.

Expected: an expired link's resolution attempt should not be counted as a click, since
the visitor was not actually sent anywhere.

Actual: the click is recorded regardless of whether the link had already expired.

This is affecting trust in the analytics numbers for any link that outlives its expiry
while still receiving traffic.
