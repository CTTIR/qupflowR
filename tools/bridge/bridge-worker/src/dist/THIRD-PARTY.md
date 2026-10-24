# Third-party software

The qupflowR bridge jars in this distribution contain only classes of the qupflowR project
(`io.github.cttir.qupflowr.*`), licensed under the GNU General Public License version 3 or later
(see `LICENSE`). No third-party code is bundled.

At run time the worker uses libraries of the local QuPath 0.7.0 installation from
`<QUPATH_HOME>/lib/app` (not redistributed here), among them:

| Component | Used for | Licence (as published by its project) |
|---|---|---|
| QuPath 0.7.0 (`qupath-core` and others) | version detection; later images and projects | GPL-3.0 |
| Gson 2.13.2 | JSON serialisation | Apache-2.0 |
| SLF4J 2.0.17, Logback 1.5.23 | logging | MIT; EPL-1.0 / LGPL-2.1 |
| Apache Groovy 5.0.4 | runs the bootstrap script (`qupath-script` profile) | Apache-2.0 |

The Java runtime (Java 25) is also provided by the system or by QuPath. Build-time tools (Gradle,
JUnit) are not part of this distribution.
