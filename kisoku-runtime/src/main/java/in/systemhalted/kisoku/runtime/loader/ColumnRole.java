package in.systemhalted.kisoku.runtime.loader;

/** Defines the role of a column in the decision table. */
enum ColumnRole {
  INPUT,
  OUTPUT,
  METADATA;

  static ColumnRole fromOrdinal(int ordinal) {
    return switch (ordinal) {
      case 0 -> INPUT;
      case 1 -> OUTPUT;
      case 2 -> METADATA;
      default -> throw new IllegalArgumentException("Unknown column role ordinal: " + ordinal);
    };
  }
}
