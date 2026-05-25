| Raw Status (lowercased, trimmed)                   | Normalized Status                            |
|----------------------------------------------------|----------------------------------------------|
| _blank_                                            | unknown                                      |
| `unknown`                                          | unknown                                      |
| `tbd`                                              | unknown                                      |
| `n/a`                                              | unknown                                      |
| `nnnn`                                             | unknown                                      |
| starts with `provisional\|` (template placeholder) | unknown                                      |
| starts with `implemented`                          | accepted                                     |
| starts with `imlpemented` (typo)                   | accepted                                     |
| starts with `rejected`                             | rejected                                     |
| starts with `replaced`                             | rejected                                     |
| starts with `withdrawn`                            | withdrawn                                    |
| starts with `deferred`                             | withdrawn                                    |
| starts with `removed`                              | withdrawn                                    |
| starts with `implementable`                        | review                                       |
| starts with `implementeable` (typo)                | review                                       |
| starts with `implementables`                       | review                                       |
| `alpha`                                            | review                                       |
| `beta`                                             | review                                       |
| starts with `provisional`                          | draft                                        |
| starts with `proposed`                             | draft                                        |
| `draft` or starts with `draft `                    | draft                                        |
| starts with `superseded`                           | superseded                                   |
| _anything else_                                    | unknown (logged as `MISSING_STATUS_MAPPING`) |