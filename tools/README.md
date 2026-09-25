# Statement test fixtures

These scripts make the blind statement tests in `app/src/test/java/.../core/StatementReaderPdfTest.kt`.

Each script:

1. Draws made-up bank statements as real PDFs, with proportional fonts and right-aligned amounts. The amounts are calculated so every statement adds up.
2. Reads the PDFs back character by character with pdfplumber.
3. Writes one `.tsv` file per statement to `app/src/test/resources/statements/`, containing each character's page, x, y, width, size and text, with the expected answers as `#key=value` lines at the top.

That character data is the same input the app gets from PdfBox on the phone.

Each round was written and scored before the reader was changed for it:

| Round | Score before changes | Gaps it found |
|---|---|---|
| 1 | 4/8 | figures above their labels, terse abbreviations |
| 2 | 6/8 | figures before their labels, figures only in sentences |
| 3 | 5/6 | "balance at start/end" wording |

To make a new round: `pip install reportlab pdfplumber`, then `python3 tools/statement_fixtures_round3.py <out-dir>`. Copy the `.tsv` files into the resources folder and add their names to the test.
