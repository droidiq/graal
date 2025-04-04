---
layout: ni-docs
toc_group: how-to-guides
link_title: Include Reachability Metadata Using the Native Image Maven Plugin
permalink: /reference-manual/native-image/guides/use-reachability-metadata-repository-maven/
---

# Include Reachability Metadata Using the Native Image Maven Plugin

You can build a native executable from a Java application with **Maven**. 
For that, use the GraalVM Native Image Maven plugin provided as part of the [Native Build Tools project](https://graalvm.github.io/native-build-tools/latest/maven-plugin.html).

A "real-world" Java application likely requires some Java reflection objects, or it calls some native code, or accesses resources on the class path - dynamic features that the `native-image` tool must be aware of at build time, and provided in the form of [metadata](../ReachabilityMetadata.md). 
(Native Image loads classes dynamically at build time, and not at run time.)

Depending on your application dependencies, there are three ways to provide the metadata:

1. [Using the GraalVM Reachability Metadata Repository](#build-a-native-executable-using-the-graalvm-reachability-metadata-repository)
2. [Using the Tracing Agent](#build-a-native-executable-with-the-tracing-agent)
3. [Autodetecting](https://graalvm.github.io/native-build-tools/latest/gradle-plugin-quickstart.html#build-a-native-executable-with-resources-autodetection) (if the required resources are directly available on the class path, in the _src/main/resources/_ directory)

This guide demonstrates how to build a native executable using the [GraalVM Reachability Metadata Repository](https://github.com/oracle/graalvm-reachability-metadata), and with the [Tracing Agent](https://graalvm.github.io/native-build-tools/latest/gradle-plugin.html#agent-support).
The goal of this guide is to illustrate the difference between the two approaches, and demonstrate how the use of reachability metadata can simplify your development tasks.

We recommend that you follow the instructions and create the application step-by-step. 
Alternatively, you can go right to the [completed example](https://github.com/graalvm/native-build-tools/tree/master/samples/metadata-repo-integration).

## Prepare a Demo Application

### Prerequisite 
Make sure you have installed a GraalVM JDK.
The easiest way to get started is with [SDKMAN!](https://sdkman.io/jdks#graal).
For other installation options, visit the [Downloads section](https://www.graalvm.org/downloads/).

1. Create a new Java project with **Maven** in your favorite IDE or from the command line, called "H2Example", in the `org.graalvm.example` package.

2. Open the main class file, _src/main/java/org/graalvm/example/H2Example.java_, and replace its contents with the following:
    ```java
    package org.graalvm.example;

    import java.sql.Connection;
    import java.sql.DriverManager;
    import java.sql.PreparedStatement;
    import java.sql.ResultSet;
    import java.sql.SQLException;
    import java.util.ArrayList;
    import java.util.Comparator;
    import java.util.HashSet;
    import java.util.List;
    import java.util.Set;

    public class H2Example {

        public static final String JDBC_CONNECTION_URL = "jdbc:h2:./data/test";

        public static void main(String[] args) throws Exception {
            withConnection(JDBC_CONNECTION_URL, connection -> {
                connection.prepareStatement("DROP TABLE IF EXISTS customers").execute();
                connection.commit();
            });

            Set<String> customers = Set.of("Lord Archimonde", "Arthur", "Gilbert", "Grug");

            System.out.println("=== Inserting the following customers in the database: ");
            printCustomers(customers);

            withConnection(JDBC_CONNECTION_URL, connection -> {
                connection.prepareStatement("CREATE TABLE customers(id INTEGER AUTO_INCREMENT, name VARCHAR)").execute();
                PreparedStatement statement = connection.prepareStatement("INSERT INTO customers(name) VALUES (?)");
                for (String customer : customers) {
                    statement.setString(1, customer);
                    statement.executeUpdate();
                }
                connection.commit();
            });

            System.out.println("");
            System.out.println("=== Reading customers from the database.");
            System.out.println("");

            Set<String> savedCustomers = new HashSet<>();
            withConnection(JDBC_CONNECTION_URL, connection -> {
                try (ResultSet resultSet = connection.prepareStatement("SELECT * FROM customers").executeQuery()) {
                    while (resultSet.next()) {
                        savedCustomers.add(resultSet.getObject(2, String.class));
                    }
                }
            });

            System.out.println("=== Customers in the database: ");
            printCustomers(savedCustomers);
        }

        private static void printCustomers(Set<String> customers) {
            List<String> customerList = new ArrayList<>(customers);
            customerList.sort(Comparator.naturalOrder());
            int i = 0;
            for (String customer : customerList) {
                System.out.println((i + 1) + ". " + customer);
                i++;
            }
        }

        private static void withConnection(String url, ConnectionCallback callback) throws SQLException {
            try (Connection connection = DriverManager.getConnection(url)) {
                connection.setAutoCommit(false);
                callback.run(connection);
            }
        }

        private interface ConnectionCallback {
            void run(Connection connection) throws SQLException;
        }
    }
    ```

3. Open the project configuration file, _pom.xml_, and replace its contents with the following:
    ```xml
    <?xml version="1.0" encoding="UTF-8"?>
    <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
            http://maven.apache.org/xsd/maven-4.0.0.xsd">
        <modelVersion>4.0.0</modelVersion>

        <groupId>org.graalvm.buildtools.examples</groupId>
        <artifactId>maven</artifactId>
        <version>1.0.0-SNAPSHOT</version>

        <properties>
            <h2.version>2.2.220</h2.version>
            <maven.compiler.source>21</maven.compiler.source>
            <maven.compiler.target>21</maven.compiler.target>
            <native.maven.plugin.version>0.10.3</native.maven.plugin.version>
            <mainClass>org.graalvm.example.H2Example</mainClass>
            <imageName>h2example</imageName>
        </properties>

        <dependencies>
            <!-- 1. H2 Database dependency -->
            <dependency>
                <groupId>com.h2database</groupId>
                <artifactId>h2</artifactId>
                <version>${h2.version}</version>
            </dependency>
        </dependencies>
        <!-- 2. Native Image Maven plugin within a Maven profile -->
        <profiles>
            <profile>
                <id>native</id>
                <build>
                    <plugins>
                        <plugin>
                            <groupId>org.graalvm.buildtools</groupId>
                            <artifactId>native-maven-plugin</artifactId>
                            <version>0.10.3</version>
                            <extensions>true</extensions>
                            <executions>
                                <execution>
                                    <id>build-native</id>
                                    <goals>
                                        <goal>compile-no-fork</goal>
                                    </goals>
                                    <phase>package</phase>
                                </execution>
                            </executions>
                            <configuration>
                                <buildArgs>
                                    <!-- 3. Quick build mode -->
                                    <buildArg>-Ob</buildArg>
                                </buildArgs>
                            </configuration>
                        </plugin>
                    </plugins>
                </build>
            </profile>
        </profiles>
        <build>
            <finalName>${project.artifactId}</finalName>
            <plugins>
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-assembly-plugin</artifactId>
                    <version>3.7.0</version>
                    <configuration>
                        <descriptorRefs>
                            <descriptorRef>jar-with-dependencies</descriptorRef>
                        </descriptorRefs>
                        <archive>
                            <manifest>
                                <mainClass>${mainClass}</mainClass>
                            </manifest>
                        </archive>
                    </configuration>
                    <executions>
                        <execution>
                            <id>assemble-all</id>
                            <phase>package</phase>
                            <goals>
                                <goal>single</goal>
                            </goals>
                        </execution>
                    </executions>
                </plugin>
            </plugins>
        </build>
    </project>
    ```
    **1** Add a dependency on the [H2 Database](https://www.h2database.com/html/main.html), an open source SQL database for Java. The application interacts with this database through the JDBC driver.

    **2** Enable the [Native Image Maven plugin](https://graalvm.github.io/native-build-tools/latest/maven-plugin.html) within a Maven profile, attached to the `package` phase. You are going to build a native executable using a Maven profile. A Maven profile allows you to decide whether to just build a JAR file, or a native executable. The plugin discovers which JAR files it needs to pass to `native-image` and what the executable main class should be.

    **3** You can pass parameters to the underlying `native-image` build tool using the `<buildArgs>` section. In individual `<buildArg>` tags you can pass parameters exactly the same way as you do on the command line. The `-Ob` option to enable the quick build mode (recommended during development only) is used as an example. Learn about other configuration options from the [plugin's documentation](https://graalvm.github.io/native-build-tools/latest/maven-plugin.html#configuration-options).

4. (Optional) Build the application:
    ```
    mvn -DskipTests clean package
    ```
    This generates an executable JAR file.

## Build a Native Executable Using the GraalVM Reachability Metadata Repository

[GraalVM Reachability Metadata repository](https://github.com/oracle/graalvm-reachability-metadata) provides GraalVM configuration for libraries which do not support GraalVM Native Image by default.
One of these is the [H2 Database](https://www.h2database.com/html/main.html) this application depends on.

The Native Image Maven plugin **automatically downloads the metadata from the repository at build time**.

1. Build a native image:
    ```shell
    mvn -DskipTests -Pnative package
    ```
    This generates an executable file for the platform in the _target/_ directory, called `h2example`.
    Notice the new directory _target/graalvm-reachability-metadata_ where the metadata is pulled into.

2. Run the application from the native executable which should return a list of customers stored in the H2 Database:
    ```shell
    ./target/h2example 
    ```

3. Run `mvn clean` to clean up the project and delete the metadata directory with its contents before you continue.

## Build a Native Executable with the Tracing Agent

The second way to provide the medatata configuration for `native-image` is by injecting the [Tracing Agent](https://graalvm.github.io/native-build-tools/latest/maven-plugin.html#agent-support) (later *the agent*) at compile time.
The agent is disabled by default, but it can be enabled within your _pom.xml_ file or via the command line.

The agent can run in three modes:
- **Standard**: Collects metadata without conditions. This is recommended if you are building a native executable. (Default)
- **Conditional**: Collects metadata with conditions. This is recommended if you are creating conditional metadata for a native shared library intended for further use.
- **Direct**: For advanced users only. This mode allows directly controlling the command line passed to the agent.

See below how to collect metadata with the Tracing Agent, and build a native executable applying the provided configuration.
Before you continue, clean the project from the previous build: `mvn clean`.

1. Enable the agent by adding the following into the `<configuration>` element of the `native` profile:
    ```xml
    <agent>
        <enabled>true</enabled>
    </agent>
    ```
    The configuration block should resemble this:
    ```xml
    <configuration>
        <agent>
            <enabled>true</enabled>
        </agent>
        <buildArgs>
            <buildArg>-Ob</buildArg>
        </buildArgs>
    </configuration>
    ```

2. Executing your application with the agent is more involved and requires you to configure a separate MOJO execution which allows forking a Java process. 
In the `native` Maven profile section, add the `exec-maven-plugin` plugin:
    ```xml
    <plugin>
        <groupId>org.codehaus.mojo</groupId>
        <artifactId>exec-maven-plugin</artifactId>
        <version>3.1.1</version>
        <executions>
            <execution>
                <id>java-agent</id>
                <goals>
                    <goal>exec</goal>
                </goals>
                <phase>test</phase>
                <configuration>
                    <executable>java</executable>
                    <workingDirectory>${project.build.directory}</workingDirectory>
                    <arguments>
                        <argument>-classpath</argument>
                        <classpath/>
                        <argument>${mainClass}</argument>
                    </arguments>
                </configuration>
            </execution>
        </executions>
    </plugin>
    ```

3. Run your application with the agent on the JVM:
    ```shell
    mvn -Pnative -DskipTests -DskipNativeBuild=true package exec:exec@java-agent
    ```
    The agent captures and records calls to the H2 Database and all the dynamic features encountered during a test run into the _reachability-metadata.json_ file in the _target/native/agent-output/main/_ directory.

4. Build a native executable using configuration collected by the agent:
    ```shell
    mvn -Pnative -DskipTests package
    ```
    It generates a native executable for the platform in the _target/_ directory, called _h2example_.

5. Run the application from the native executable:
    ```shell
    ./target/h2example
    ```

### Summary

This guide demonstrated how to build a native executable using the [GraalVM Reachability Metadata Repository](https://github.com/oracle/graalvm-reachability-metadata) and with the Tracing Agent.
The goal was to show the difference, and prove how using the reachability metadata can simplify the work.
Using the GraalVM Reachability Metadata Repository enhances the usability of Native Image for Java applications depending on 3rd party libraries.

### Related Documentation

- [Reachability Metadata](../ReachabilityMetadata.md)
- [Collect Metadata with the Tracing Agent](../AutomaticMetadataCollection.md#tracing-agent)
- [Native Image Build Tools](https://graalvm.github.io/native-build-tools/latest/index.html)