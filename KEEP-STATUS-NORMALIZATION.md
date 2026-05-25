| Raw Status (lowercased, trimmed)                     | Normalized Status                            |
|------------------------------------------------------|----------------------------------------------|
| _blank_                                              | unknown                                      |
| `unknown`                                            | unknown                                      |
| `tbd`                                                | unknown                                      |
| `n/a`                                                | unknown                                      |
| contains `stable`                                    | accepted                                     |
| contains `implemented`                               | accepted                                     |
| contains `experimental`                              | accepted                                     |
| contains `beta`                                      | accepted                                     |
| `accepted` or starts with `accepted `                | accepted                                     |
| `approved` or starts with `approved `                | accepted                                     |
| `published` or starts with `published `              | accepted                                     |
| contains `preview`                                   | accepted                                     |
| starts with `available`                              | accepted                                     |
| contains `rejected`                                  | rejected                                     |
| contains `declined`                                  | rejected                                     |
| contains `withdrawn`                                 | withdrawn                                    |
| contains `abandoned`                                 | withdrawn                                    |
| contains `deprecated`                                | withdrawn                                    |
| contains `obsolete`                                  | withdrawn                                    |
| contains `in progress`                               | review                                       |
| contains `in design`                                 | review                                       |
| contains `discussion` or `discussing` or `discussed` | review                                       |
| contains `review`                                    | review                                       |
| contains `under consideration`                       | review                                       |
| contains `working on`                                | review                                       |
| contains `prototype` or `prototyped`                 | review                                       |
| contains `pending`                                   | review                                       |
| contains `submitted`                                 | draft                                        |
| contains `proposed`                                  | draft                                        |
| `draft` or starts with `draft `                      | draft                                        |
| `design` or `design proposal`                        | draft                                        |
| contains `superseded`                                | superseded                                   |
| contains `superceded` (typo)                         | superseded                                   |
| _anything else_                                      | unknown (logged as `MISSING_STATUS_MAPPING`) |
