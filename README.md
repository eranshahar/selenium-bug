Reproducing lethal selenium bug.

The bug causes wrong screenshot made by the selenium. 

When you request concurrently from selenium,
to take a screenshot of different screens (different url navigated),
same screenshot returns from both urls instead of 2 unique screenshots.

This code reproduce the issue.

It sends concurrently 20 screenshots requests for 20 different urls,
And validate the output it's 20 unique screenshot,
Its run in a loop until bug occurrence.

The bug reproduces on versions 3.6.0 and above.

Please advise.  

Eran & Ofir.
