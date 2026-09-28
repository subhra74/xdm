# Build, test & toolchain details

Plain Maven project (no wrapper; uses a system `mvn`). Reactor root is `pom.xml`, modules `xdm-core`, `xdm-app`, `hls-muxer`.

```bash
mvn -q clean package              # build all modules; produces xdm-app/target/xdm-app.jar (fat jar)
mvn -q -pl xdm-app -am package    # build xdm-app and its dependencies only
mvn -q test -DskipTests=false                       # run all tests
mvn -q -pl xdm-core test -DskipTests=false          # test a single module
mvn -q -pl xdm-core test -DskipTests=false -Dtest=SomeTest          # single test class
mvn -q -pl xdm-core test -DskipTests=false -Dtest=SomeTest#method   # single test method
```

Run: `java -jar xdm-app/target/xdm-app.jar` (main class `xdm.app.AppMain`); `--no-gc` disables the periodic `System.gc()` thread.

## Notes
- Kotlin compiles via `kotlin-maven-plugin`; the default `maven-compiler-plugin` `default-compile`/`default-testCompile`
  executions are deliberately disabled and re-bound so Kotlin and Java sources interop. Source roots are `src/main/java`
  even though they hold Kotlin (`xdm-core`/`xdm-app`); `hls-muxer` uses `src/main/kotlin`.
- JVM target differs by module: `xdm-app` targets **JDK 25** (uses `java.lang.foreign` for Windows integration);
  `xdm-core` and `hls-muxer` stay on **JDK 8** and must keep working there.
- **The JDK 25 compiler is selected through a Maven toolchain, not `JAVA_HOME`.** Copy `packaging/toolchains.sample.xml`
  to `~/.m2/toolchains.xml` and point `jdkHome` at a JDK 25. Without a matching entry the build stops with
  "Cannot find matching toolchain definitions". (Homebrew `mvn` forces its own openjdk; IntelliJ builds with the JetBrains Runtime.)
- Kotlin must stay at **2.3.21 or newer**: 2.3.0 cannot resolve `java.lang.foreign`'s `allocateFrom` overloads when the
  compiler runs on the JetBrains Runtime (misleading "unresolved reference" in `xdm-app`'s Windows layer).
- Tests are **JUnit 5** (`org.junit.jupiter`) in packages `xdm` (xdm-app) and `xdm.core` (xdm-core); keep new ones in a
  package. Jupiter puts the assertion message **last** (`assertEquals(expected, actual, message)`).
- Surefire is pinned to **3.5.6**, not 3.6.0: under 3.6.0 `reuseForks=false` discovers no tests and still reports
  BUILD SUCCESS. xdm-core needs that setting (fresh JVM per class keeps leaked threads/sockets from bleeding between
  networked download tests). If you bump it, check test COUNTS: xdm-core must run 175, xdm-app 98.
- `kotlinx-serialization` is enabled as a Kotlin compiler plugin for JSON models.

## Runtime layout
At startup the app creates `~/.xdm-app/` (and `~/.xdm-app/tmp/`): config, downloads DB, per-task `task-<id>.info`, and
`<id>.state` resume files. (`Constants.kt` also defines a legacy `.xdman` dir name that `AppMain` does not use.)
