# Data-sharing note

*To be countersigned by the instructor before any marks are received.*

## What we receive

One row per marked response: student, question, assistant, score, and where available the
script's position in that assistant's sequence. Nothing else. No names, no roll numbers where
they can be avoided, no demographic fields, no free-text comments.

## What happens at ingest

Student and assistant identifiers are replaced by HMAC-SHA-256 digests under a 256-bit salt
generated for that run, **before anything is written to disk**. Only the digest reaches the
database. The salt is written once to a key file under `keys/`, which the instructor keeps and
which the application never reads back. A test asserts that no identifier from the input appears
anywhere in the bytes of the database file.

## Where the data lives

On the instructor's own machine. The tool runs as a local process bound to `127.0.0.1`, or in a
container. There is no hosted deployment, no external API, and no outbound network call; an
architecture test asserts that no module can reach an HTTP client. Marks and identities never
leave the machine they are loaded on.

## What we publish

Results may be reported in full, whatever they show. Assistants are pseudonymised and the course
is identified only by size and structure. No output ranks named individuals. Every severity
figure carries its interval, so an assistant who marked thirty scripts is visibly less precisely
estimated than one who marked three hundred. Assistants who are *erratic* are reported separately
from assistants who are *harsh*, because the two are different findings and conflating them would
be unfair to both.

Severity is a property of a marking session, not a verdict on a person, and every output is
worded that way.

## What the instructor holds alone

The key mapping pseudonyms to assistants, and the re-grade shortlist. Students never touch the
system: their identifiers never enter the database and nothing is ever reported at the level of a
named student.

## Withdrawal

The instructor may ask for the data and every derived artefact to be deleted at any point, for
any reason, without explanation. Deletion covers the database, the key file, and any run
manifest.

---

Instructor: ______________________  Date: ____________

Students: Mohamed Zayaan S (CE23B092), Pruthviraj Milind Chougale (CE23B104)
