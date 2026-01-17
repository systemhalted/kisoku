package in.systemhalted.kisoku.api.model;

/** The role of a column in rule evaluation. */
public enum ColumnRole {
  /** Input column used for matching conditions. */
  INPUT,
  /** Output column that provides result values. */
  OUTPUT,
  /** Metadata column (RULE_ID, PRIORITY) not used in matching. */
  METADATA
}
