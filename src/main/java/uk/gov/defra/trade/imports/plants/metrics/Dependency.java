package uk.gov.defra.trade.imports.plants.metrics;

/** A dependency whose calls are recorded, with the value of its {@code dependency} tag. */
public enum Dependency {
  MONGODB("mongodb");

  private final String tagValue;

  Dependency(String tagValue) {
    this.tagValue = tagValue;
  }

  public String tagValue() {
    return tagValue;
  }
}
