# Eclipse Xpect

Eclipse Xpect™ is a unit- and integration-testing framework to be used for Xtext-based languages. Test data (e.g. expectations) are embedded into programs written in the DSL under tests. Xpect itself is based on Junit; new test methods can be written in Java and are called by the Xpect framework with the test data. Typical Xpect tests include expecting certain error messages, ensuring correct scopes, or specific content assist proposals.

## Documentation

Visit [xpect-tests.org](http://www.xpect-tests.org) for details.

#### Nightly builds

Use Jenkins https://ci.eclipse.org/xpect/job/Xpect/job/master/lastSuccessfulBuild/artifact/org.eclipse.xpect.releng/p2-repository/target/repository/


# Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for details.

## Compile and Build Xpect by Yourself

Prerequisite: Java 17 or newer; Eclipse (2023-03 or newer recommended); Xtext 2.31.0.

 1. Clone https://github.com/eclipse-xpect/Xpect (this repository)
 2. Import all projects into your Eclipse workspace.
 3. Set target platform to xpect_latest.target (Preferences -> Plug-in Development -> Target Platform)
 4. Run /org.eclipse.xpect/src/org/eclipse/xpect/GenerateXpect.mwe2, /org.xtext.example.arithmetics/src/org/eclipse/xpect/example/arithmetics/GenerateXpect.mwe2, /org.xtext.example.domainmodel/src/org/xtext/example/domainmodel/GenerateDomainmodel.mwe2. Now your projects should be without errors markers. Sometimes, even after these steps, several projects still have error markers. However, this is a refresh problem in Eclipse. Simply clean build the projects with error markers will solve the issues.
 5. Run `mvn -P '!tests'  -Dtarget-platform=xpect_latest --batch-mode --update-snapshots -fae -Dmaven.repo.local=xpect-local-maven-repository -DtestOnly=false clean install` to build artefacts and create a p2 repository (formerly known as update site).
