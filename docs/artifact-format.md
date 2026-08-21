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

## Header (32 bytes)

| Offset | Size | Field | Description |
|--------|------|-------|-------------|
| 0 | 4 | magic | Magic bytes: `0x4B495353` ("KISS") |
| 4 | 2 | version_major | Format major version (currently 2) |
| 6 | 2 | version_minor | Format minor version (currently 0) |
| 8 | 1 | artifact_kind | 0 = PRODUCTION, 1 = TEST_INCLUSIVE |
| 9 | 1 | rule_selection | 0 = AUTO, 1 = PRIORITY, 2 = FIRST_MATCH |
| 10 | 2 | reserved | Reserved for future use |
| 12 | 4 | column_count | Number of columns |
| 16 | 4 | row_count | Number of rules (rows) |
| 20 | 4 | dictionary_offset | Byte offset to string dictionary |
| 24 | 4 | columns_offset | Byte offset to column definitions |
| 28 | 4 | data_offset | Byte offset to rule data |

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

Each column definition (16 bytes):

| Size | Field | Description |
|------|-------|-------------|
| 4 | name_id | Dictionary ID of column name |
| 1 | operator | Operator enum ordinal |
| 1 | column_type | Type enum ordinal |
| 1 | column_role | 0 = INPUT, 1 = OUTPUT, 2 = METADATA |
| 1 | flags | Bit flags (see below) |
| 4 | data_offset | Byte offset of this column's data, relative to the rule data section base |
| 4 | scale | Decimal scale for `DECIMAL` columns (the number of fractional digits every value in the column is stored at); 0 for all other types |

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

## Rule Order Index

For deterministic evaluation, rules are stored in priority or insertion order:

```
┌─────────────────────────────────────────┐
│ order_type (1 byte)                     │
│ rule_indices[row_count] (4 bytes each)  │
└─────────────────────────────────────────┘
```

- `order_type`: 0 = insertion order, 1 = priority order
- `rule_indices`: Row indices in evaluation order

If `rule_selection = PRIORITY`, rules are pre-sorted by priority value **ascending** — a lower
value means a higher priority, so `PRIORITY` 1 is evaluated before 2 — with ties broken by
source order and unnumbered rows sorted last. If `rule_selection = FIRST_MATCH`, rules are in original CSV row order.

Rows are written to the rule data section **already in evaluation order**, so `rule_indices`
is the identity permutation over physical rows. It must not hold the source-row permutation:
the loader treats each entry as a physical row index, so storing the permutation as well would
apply the ordering twice.

## Versioning

- **Major version change**: Breaking format change, old loaders cannot read new artifacts
- **Minor version change**: Backward-compatible additions, old loaders can read new artifacts

Current version: 2.0

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
1. Header: magic=KISS, version=2.0, columns=3, rows=2
2. Dictionary: ["R1", "R2", "0.10", "0.15"]
3. Column defs: RULE_ID (RULE_ID, STRING), AGE (GTE, INTEGER), DISCOUNT (SET, DECIMAL)
4. Rule data:
   - RULE_ID: presence=[1,1], values=[0,1] (dict IDs)
   - AGE: presence=[1,1], values=[18,21] (raw integers)
   - DISCOUNT: presence=[1,1], values=[2,3] (dict IDs for decimals as strings)
5. Rule order: [0, 1] (insertion order)
