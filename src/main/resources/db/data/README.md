# Reference data

## `nigerian-states.sql` — Nigeria's 36 states and the FCT

- **Source**: GRID3 Nigeria operational state boundaries, published by
  geoBoundaries (William & Mary geoLab), gbOpen release, NGA ADM1, commit
  `9469f09`. Boundaries represent 2022; source data updated 2023-02-26.
  <https://www.geoboundaries.org/api/current/gbOpen/NGA/ADM1/>
- **Licence**: Creative Commons Attribution 4.0 (CC BY 4.0). **Attribution is
  required** wherever the data is used or shown — e.g. "State boundaries:
  GRID3, via geoBoundaries (CC BY 4.0)" in the API documentation and on any
  map or credits page that relies on it.
- **Status**: operational boundaries, not the legal boundary record. Some
  state borders are disputed; the SB-1 check has a Super Admin override for
  exactly that (see AGENTS.md).
- **Regenerate**: `scripts/generate-nigerian-states-sql.py` (instructions in
  the script). A new dataset version is a **new** Liquibase changeset, never
  an edit to changeset 064.
