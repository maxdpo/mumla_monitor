@echo off
REM IW2DPO - Diagnosi avvio automatico MumlaMonitor sul Hytera P50pro
REM Uso: riavvia la radio, aspetta 3 minuti, collega USB e fai doppio clic su questo file.
set ADB=C:\platform-tools\adb.exe
if not exist "%ADB%" set ADB=adb
set PKG=it.iw2dpo.mumlamonitor
set OUT=%~dp0diagnosi_boot.txt

echo Raccolta dati dalla radio in corso...
(
echo ===== DATA
date /t
time /t
echo ===== DISPOSITIVO
"%ADB%" devices
"%ADB%" shell getprop ro.product.model
"%ADB%" shell getprop ro.build.version.release
"%ADB%" shell getprop ro.build.display.id
echo ===== UPTIME
"%ADB%" shell uptime
echo ===== BLOCCO SCHERMO
"%ADB%" shell dumpsys trust
echo ===== PROCESSI MumlaMonitor / Mumla
"%ADB%" shell pidof %PKG%
"%ADB%" shell pidof se.lublin.mumla.beta
"%ADB%" shell pidof se.lublin.mumla
echo ===== RECEIVER BOOT_COMPLETED REGISTRATI
"%ADB%" shell pm query-receivers --brief -a android.intent.action.BOOT_COMPLETED
echo ===== SERVIZIO WATCHDOG
"%ADB%" shell dumpsys activity services %PKG%
echo ===== APPOPS (overlay, background)
"%ADB%" shell appops get %PKG%
echo ===== WHITELIST BATTERIA
"%ADB%" shell dumpsys deviceidle whitelist
echo ===== STANDBY BUCKET
"%ADB%" shell am get-standby-bucket %PKG%
echo ===== LOGCAT MumlaMonitor
"%ADB%" logcat -d -s MumlaMonitor
echo ===== LOGCAT avvio e blocchi
"%ADB%" logcat -d | findstr /i "mumlamonitor mumla.beta BOOT_COMPLETED autostart autorun forbid prevent"
echo ===== LOGCAT righe con il pacchetto (blocchi ActivityManager / firmware)
"%ADB%" logcat -d | findstr /i "it.iw2dpo"
echo ===== CODA BROADCAST (BOOT_COMPLETED in attesa?)
"%ADB%" shell dumpsys activity broadcasts | findstr /i "mumlamonitor BOOT_COMPLETED"
echo ===== LOGCAT EVENTI
"%ADB%" logcat -b events -d | findstr /i "mumla boot_progress"
echo ===== PACKAGE MumlaMonitor
"%ADB%" shell dumpsys package %PKG%
) > "%OUT%" 2>&1

echo.
echo Fatto. Risultati salvati in: %OUT%
pause
