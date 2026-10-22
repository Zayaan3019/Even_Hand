# Data-sharing note

*To be countersigned by the instructor before any marks are received.*

## What we receive

One row per marked response: student, question, assistant, score, the marks the question was out
of, and where available the assessment, the script's position in that assistant's sequence, and
whether the mark is the one that counts or a second, checking mark. Nothing else. No names of
students, no demographic fields, no free-text comments.

The first thing we run needs no scores at all: who marked what is enough to say whether the
arrangement could have disadvantaged anyone, and whether that can be measured.

## What happens at ingest

Student and assistant identifiers are replaced by HMAC-SHA-256 digests under a 256-bit salt
generated for the course, **before any record of a response exists**. Pseudonyms are numbered in
the order of those keyed digests, never in the order the file lists people, so a label such as
"Student 7" says nothing about anyone's place in a roll list.

While the roll numbers are still in memory, ingest records two statistics about how the scripts
were divided — whether each assistant's pile is a contiguous block of roll numbers, and whether piles
follow the department code — as counts. Nothing is kept that places any individual in the roll list.

The mapping from pseudonyms back to identifiers, together with the salt, forms the key file. It is
encrypted with AES-256-GCM under a key derived from a passphrase that only the instructor knows
(PBKDF2, 600,000 iterations) **before it is written anywhere**. Without the passphrase it cannot be
opened, and any alteration makes it fail to open. The application reads it back only when the
instructor opens it to re-run the same course, so that a quiz and the end-semester exam of one course
use the same pseudonyms. The salt of a course's first run is derived from its input file, so running
the same file twice gives the same pseudonyms and the same figures; recomputing it needs the file
itself, which already holds the identities. A new offering's file gives a new, unrelated salt.

Tests assert that no identifier from the input survives ingest anywhere except inside the sealed key
file; once the database exists, the same test scans its bytes.

## Where the data lives

On the instructor's own machine. The tool runs as a local process bound to `127.0.0.1`, or in a
container. There is no hosted deployment, no external API, and no outbound network call; the
application module will carry an architecture test that forbids any module from reaching an HTTP
client or opening an outbound socket. Marks and identities never leave the machine they are loaded
on.

## What we publish

Results may be reported in full, whatever they show. Assistants are pseudonymised and the course
is identified only by size and structure. Pseudonyms protect an assistant little where the division
of marking is common knowledge — in question-wise marking "the Q3 marker" is a name — so figures
about individual assistants go only to the instructor, an assistant may be shown only their own, and
the Class Committee sheet and anything published carry aggregates only. Every severity figure carries
its interval, so an assistant who marked thirty scripts is visibly less precisely estimated than one
who marked three hundred. Assistants who are *erratic* are reported separately from assistants who
are *harsh*, because the two are different findings and conflating them would be unfair to both.

Severity is a property of a marking session, not a verdict on a person, and every output is worded
that way. No output of the tool changes a mark: it can only recommend that a script be looked at
again, and a grade changes only by a human re-mark under the instructor's normal procedure.

## What the instructor holds alone

The key file and its passphrase, and the re-grade shortlist. Students never touch the system: their
identifiers never enter the database and nothing is ever reported at the level of a named student.

## Withdrawal

The instructor may ask for the data and every derived artefact to be deleted at any point, for
any reason, without explanation. Deletion covers the database, the key file, and any run
manifest. Handling follows the principles of the Digital Personal Data Protection Act, 2023:
the minimum data for the stated purpose, processed locally, and deleted on request.

---

Instructor: ______________________  Date: ____________

Students: Mohamed Zayaan S (CE23B092), Pruthviraj Milind Chougale (CE23B104)
