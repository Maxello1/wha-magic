# Workspace Rules

## Always Build After Changes
Always run `.\gradlew clean build` (with the correct JAVA_HOME) after making *any* changes to the repository, including resource file changes (JSON, PNG, etc). The user relies on the built artifacts being up to date and does not want to ask for this step manually.
