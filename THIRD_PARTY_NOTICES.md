# Licenses and acknowledgments

The [MIT license](LICENSE) applies to pocketomv's own code. Separately licensed components keep their original copyright, license and notice texts.

| Component | License | Included text |
| --- | --- | --- |
| AndroidX Activity, Compose, Lifecycle and supporting libraries | Apache-2.0 | [Apache license](app/src/main/assets/third-party/Apache-2.0.txt), [component inventory and notices](app/src/main/assets/third-party/NOTICE.txt) |
| Kotlin standard library, Kotlin coroutines and serialization, JetBrains annotations, JSpecify and Guava ListenableFuture | Apache-2.0 | [Apache license](app/src/main/assets/third-party/Apache-2.0.txt), [component inventory and notices](app/src/main/assets/third-party/NOTICE.txt) |
| Apache Commons Statistics, Numbers and RNG | Apache-2.0 | [Original license and NOTICE texts](app/src/main/assets/third-party/NOTICE.txt) |
| SciPy's complete-sample Shapiro–Wilk algorithm and coefficients, adapted in `ShapiroWilk.kt` | BSD-3-Clause | [Original copyright, conditions and disclaimer](app/src/main/assets/third-party/scipy-LICENSE.txt) |
| SciPy 1.14.1 More–Thuente safeguarded line search, adapted in `BoundedQuasiNewton.kt` | BSD-3-Clause | [Original copyright, conditions, disclaimer and MINPACK acknowledgments](app/src/main/assets/third-party/scipy-minpack2-LICENSE.txt) |
| Gradle wrapper scripts and JAR, included with the source | Apache-2.0 | Script headers and `META-INF/LICENSE` inside the wrapper JAR |

The full resolved runtime component inventory and the original archive notices are included in the application. Open **Licenses** to read these texts and pocketomv's MIT license. Retain the applicable licenses and notices when redistributing source or binaries.

Statistical definitions and reference links are in the [statistical guide](docs/statistical-methods.md). The application does not include jamovi, R, statsmodels or factor-analyzer code or binaries. The SciPy adaptations are identified separately above. jamovi is a separate project; its name and documentation references do not imply affiliation or endorsement.
