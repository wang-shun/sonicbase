SET SONIC_BASE_HOME=%2

schtasks /end /tn "BenchServer%1"
schtasks /delete /f /tn "BenchServer%1"
schtasks /create /tn "BenchServer%1" /tr "%SONIC_BASE_HOME%/bin/start-license-server.vbs %SONIC_BASE_HOME%/bin/start-license-server.bat %1 %2" /sc once /sd 01/01/2017 /st 00:00
schtasks /run /tn "BenchServer%1"
