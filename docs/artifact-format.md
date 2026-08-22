# Compiled Artifact Binary Format

This document specifies the binary format for compiled Kisoku decision table artifacts.

## Overview

A compiled artifact is a self-contained binary file that contains:
1. Header with version and metadata
2. String dictionary for value compression
3. Column definitions with operators and types
4. Encoded rule data in columnar format
5. Rule ordering for deterministic evaluation

## Format Layout

```
┌─────────────────────────────────────────┐
│ Header (32 bytes)                       │
├─────────────────────────────────────────┤
│ String Dictionary                       │
├─────────────────────────────────────────┤
│ Column Definitions                      │
├─────────────────────────────────────────┤
│ Rule Data (columnar)                    │
├─────────────────────────────────────────┤
│ Rule Order Index                        │
└─────────────────────────────────────────┘
```

## Header (64 bytes)

All section offsets are 64-bit, so the artifact as a whole may exceed 2 GB. Each individual
column's data must stay under 2 GB — the loader maps every column as its own buffer.

| Offset | Size | Field | Description |
|--------|------|-------|-------------|
| 0 | 4 | magic | Magic bytes: `0x4B495353` ("KISS") |
| 4 | 2 | version_major | Format major version (currently 4) |
| 6 | 2 | version_minor | Format minor version (currently 1) |
| 8 | 1 | artifact_kind | 0 = PRODUCTION, 1 = TEST_INCLUSIVE |
| 9 | 1 | rule_selection | 0 = AUTO, 1 = PRIORITY, 2 = FIRST_MATCH |
| 10 | 2 | reserved | Reserved for future use |
| 12 | 4 | column_count | Number of columns |
| 16 | 4 | row_count | Number of rules (rows) |
| 20 | 8 | dictionary_offset | Byte offset to string dictionary |
| 28 | 8 | columns_offset | Byte offset to column definitions |
| 36 | 8 | data_offset | Byte offset to rule data |
| 44 | 8 | rule_order_offset | Byte offset to the rule order section |
| 52 | 8 | index_offset | Byte offset to the index directory; 0 = no persisted indexes (4.0 artifacts) |
| 60 | 4 | reserved | Reserved for future use (zero) |

## String Dictionary

All string values are stored once in a dictionary and referenced by ID (4-byte int).

```
┌─────────────────────────────────────────┐
│ entry_count (4 bytes)                   │
├─────────────────────────────────────────┤
│ Entry 0: length (2 bytes) + UTF-8 bytes │
│ Entry 1: length (2 bytes) + UTF-8 bytes │
│ ...                                     │
└─────────────────────────────────────────┘
```

- `entry_count`: Number of unique strings
- Each entry: 2-byte length prefix + UTF-8 encoded string bytes
- Dictionary ID 0 is reserved for null/empty values

## Column Definitions

Each column is defined with its metadata:

```
┌─────────────────────────────────────────┐
│ Column 0 Definition                     │
│ Column 1 Definition                     │
│ ...                                     │
└─────────────────────────────────────────┘
```

Each column definition (24 bytes):

| Size | Field | Description |
|------|-------|-------------|
| 4 | name_id | Dictionary ID of column name |
| 1 | operator | Operator enum ordinal |
| 1 | column_type | Type enum ordinal |
| 1 | column_role | 0 = INPUT, 1 = OUTPUT, 2 = METADATA |
| 1 | flags | Bit flags (see below) |
| 8 | data_offset | Byte offset of this column's data, relative to the rule data section base |
| 4 | scale | Decimal scale for `DECIMAL` columns (the number of fractional digits every value in the column is stored at); 0 for all other types |
| 4 | reserved | Reserved for future use (zero) |

Definitions are written in data order: a column's data ends where the next column's begins
(the last column ends at `rule_order_offset`), which is how the loader sizes each column's
mapping without scanning its contents.

### Column Flags

Flags use powers of 2 so they can be combined with bitwise OR:

| Bit | Value | Flag | Description |
|-----|-------|------|-------------|
| 0 | 0x01 | nullable | Column allows null/blank values |
| 1 | 0x02 | test-only | Column is for testing (prefix `TEST_`); can be excluded at evaluation time |
| 2-7 | - | reserved | Reserved for future use |

Examples:
- `0x00` - required column, not test-only
- `0x01` - nullable column
- `0x02` - test-only column (required)
- `0x03` - nullable + test-only (`0x01 | 0x02`)

Test-only columns are included in all artifacts (both PRODUCTION and TEST_INCLUSIVE). The loader/evaluator can optionally exclude them at evaluation time based on `LoadOptions`.

### Operator Ordinals

| Value | Operator |
|-------|----------|
| 0 | RULE_ID |
| 1 | PRIORITY |
| 2 | SET |
| 3 | EQ |
| 4 | NE |
| 5 | GT |
| 6 | GTE |
| 7 | LT |
| 8 | LTE |
| 9 | BETWEEN_INCLUSIVE |
| 10 | BETWEEN_EXCLUSIVE |
| 11 | NOT_BETWEEN_INCLUSIVE |
| 12 | NOT_BETWEEN_EXCLUSIVE |
| 13 | IN |
| 14 | NOT_IN |

### Column Type Ordinals

| Value | Type | Java Equivalent |
|-------|------|-----------------|
| 0 | STRING | String |
| 1 | INTEGER | long |
| 2 | DECIMAL | BigDecimal |
| 3 | BOOLEAN | boolean |
| 4 | DATE | LocalDate |
| 5 | TIMESTAMP | Instant |

## Rule Data (Columnar)

Data is stored column-by-column, not row-by-row. This enables:
- Better compression (similar values grouped)
- Efficient column-wise filtering
- Cache-friendly access patterns

### Encoding by Operator Type

**Scalar operators (EQ, NE, GT, GTE, LT, LTE, SET, RULE_ID, PRIORITY):**
```
┌─────────────────────────────────────────┐
│ presence_bitmap (ceil(row_count/8) bytes)│
│ values[row_count] (8 bytes each)        │
└─────────────────────────────────────────┘
```
- `presence_bitmap`: Bit i = 1 if row i has a value (not blank)
- `values`: the value's order-preserving code (see [Value Encoding](#value-encoding))

**Range operators (BETWEEN_*, NOT_BETWEEN_*):**
```
┌─────────────────────────────────────────┐
│ presence_bitmap (ceil(row_count/8) bytes)│
│ min_values[row_count] (8 bytes each)    │
│ max_values[row_count] (8 bytes each)    │
└─────────────────────────────────────────┘
```
- Stores both min and max for each row

**RULE_ID (inline UTF-8):**
```
┌──────────────────────────────────────────┐
│ presence_bitmap (ceil(row_count/8) bytes)│
│ byte_offsets[row_count+1] (4 bytes each) │
│ utf8_blob                                │
└──────────────────────────────────────────┘
```
- Rule ids are unique per row by design, so dictionary-encoding them would make the
  dictionary linear in the row count. They are stored as raw UTF-8 instead: row i's bytes are
  `utf8_blob[byte_offsets[i] .. byte_offsets[i+1])`, decoded on demand for the one winning
  row of an evaluation. `byte_offsets[row_count]` is the blob size.

**Set operators (IN, NOT_IN):**
```
┌─────────────────────────────────────────┐
│ presence_bitmap (ceil(row_count/8) bytes)│
│ list_offsets[row_count] (4 bytes each)  │
│ list_lengths[row_count] (2 bytes each)  │
│ all_values[] (8 bytes each)             │
└─────────────────────────────────────────┘
```
- `list_offsets[i]`: Start index in `all_values` for row i
- `list_lengths[i]`: Number of values for row i (16-bit, so a set holds at most 65,535 members)
- `all_values`: Concatenated member codes for all sets

## Value Encoding

Every cell, whatever its column type, is stored as a single 64-bit **order-preserving
code**: comparing two codes gives the same answer as comparing the two values. That is what
makes `GT`/`GTE`/`LT`/`LTE` and the range operators meaningful on strings, decimals and
timestamps, not just on integers.

| Column type | Encoded from |
|-------------|--------------|
| `STRING` | 1-based rank in the sorted string dictionary |
| `INTEGER` | the value itself (full 64-bit range) |
| `DECIMAL` | unscaled value at the column's `scale` |
| `DATE` | epoch day |
| `TIMESTAMP` | epoch microseconds |
| `BOOLEAN` | 0 or 1 |

Codes are **doubled**: a value the column represents exactly encodes to `2v`, which leaves
every odd number free to mean "strictly between two representable values". An input the
compiler never saw — a string absent from the dictionary, or a decimal carrying more
precision than the column's scale — encodes as `2f + 1`, where `f` is the greatest
representable value below it. Such an input orders correctly against every stored value and
compares equal to none of them. Code `0` is reserved for a null or blank cell.

Because ranks come from the *sorted* dictionary, the loader resolves a string input by binary
search over the dictionary entries rather than by keeping a hash map of every value on the
heap.

## Rule Order Section

```
┌─────────────────────────────────────────┐
│ order_type (1 byte)                     │
│ rule_indices[row_count] (4 bytes each,  │
│   present only when order_type = 1)     │
└─────────────────────────────────────────┘
```

- `order_type` 0: rows are stored in evaluation order (the identity permutation); nothing
  follows. This is what the compiler always writes — rows are physically reordered at
  compile time, so no per-row array is needed and the loader holds no order on the heap.
- `order_type` 1: an explicit evaluation order over physical rows follows. Reserved for
  artifacts whose rows are not pre-sorted; the loader honors it via the order-faithful
  linear-scan path.

If `rule_selection = PRIORITY`, rows are pre-sorted by priority value **ascending** — a lower
value means a higher priority, so `PRIORITY` 1 is evaluated before 2 — with ties broken by
source order and unnumbered rows sorted last. If `rule_selection = FIRST_MATCH`, rows are in
original CSV row order.

## Index Section (4.1, optional)

```
┌──────────────────────────────────────────────┐
│ directory[column_count]:                     │
│   block_offset (8 bytes) - 0 = no index      │
│   block_length (8 bytes)                     │
│ blocks... (one per indexable input column)   │
└──────────────────────────────────────────────┘
```

Each block is the column's candidate index exactly as the runtime lays it out — a small
integer header (`PostingListIndex`: 4 ints; `RangeIntervalIndex`: 5 ints) followed by the
array region — so the loader constructs the index directly over a mapped slice with no
parsing or copying. The block kind is implied by the column's operator (range operators →
interval index, all others → posting list). Metadata, output, and test-only columns have
no block.

## Versioning

- **Major version change**: Breaking format change, old loaders cannot read new artifacts
- **Minor version change**: Backward-compatible additions, old loaders can read new artifacts

Current version: 4.1

- **4.1**: Adds a persisted index section: the header's `index_offset` (formerly reserved
  bytes 52–59) points at a per-column directory of `(block offset, block length)` pairs,
  each block holding one column's candidate index byte-for-byte in its runtime layout, so
  loading maps indexes instead of rebuilding them. Compatible both ways within major 4:
  a 4.0 reader ignores the trailing section; a 4.1 reader builds at load when
  `index_offset` is zero. Compiled with `CompileOptions.withPersistIndexes` (default on).

- **4.0**: All section offsets (header fields and per-column `data_offset`) are 64-bit and the
  header carries an explicit `rule_order_offset`, so artifacts may exceed 2 GB; each single
  column's data stays under 2 GB and the loader maps columns individually. Column definitions
  grew from 16 to 24 bytes; the header from 32 to 64. The rule-order section stores a 1-byte
  order type, with type 0 (identity) carrying no array. The compiler is streaming: two passes
  over the source plus a per-column stitch, so compile heap no longer scales with the table.
  **Not readable by 3.x** — recompile the artifact.

- **3.0**: The `RULE_ID` column is stored as inline UTF-8 (presence bitmap, byte offsets,
  blob) instead of dictionary codes. Rule ids are unique per row, so this removes the
  dictionary's linear-in-rows growth; ids are decoded on demand from the buffer for the
  winning row only. **Not readable by 2.x** — recompile the artifact.
- **2.0**: Values are stored as 64-bit order-preserving codes instead of 4-byte dictionary IDs
  and narrowed ints, so ordering operators work on every column type and `INTEGER` keeps its
  full range. Dictionary entries are written in sorted order, and `DECIMAL`/`TIMESTAMP` values
  are stored numerically rather than as dictionary strings. Column definitions carry a decimal
  `scale` and grew from 12 to 16 bytes. **Not readable by 1.x** — recompile the artifact.
- **1.1**: `data_offset` in each column definition holds the column's real byte offset
  (relative to the rule data section base). v1.0 wrote 0 for every column.
- **1.0**: Initial format.

## Example

A simple 2-column, 2-row table:
```
RULE_ID,AGE,DISCOUNT
RULE_ID,GTE,SET
R1,18,0.10
R2,21,0.15
```

Would produce:
1. Header: magic=KISS, version=4.0, columns=3, rows=2
2. Dictionary: ["R1", "R2", "0.10", "0.15"]
3. Column defs: RULE_ID (RULE_ID, STRING), AGE (GTE, INTEGER), DISCOUNT (SET, DECIMAL)
4. Rule data:
   - RULE_ID: presence=[1,1], values=[0,1] (dict IDs)
   - AGE: presence=[1,1], values=[18,21] (raw integers)
   - DISCOUNT: presence=[1,1], values=[2,3] (dict IDs for decimals as strings)
5. Rule order: [0, 1] (insertion order)
