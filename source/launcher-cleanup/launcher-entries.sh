#!/system/bin/sh
export CLASSPATH=/data/local/tmp/launcher-entries.jar
exec app_process /system/bin LauncherEntries "$@"
