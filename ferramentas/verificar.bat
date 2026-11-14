@echo off
setlocal
chcp 65001 >nul
if not exist C:\logs mkdir C:\logs
set LOG=C:\logs\verificar_android.txt
call :principal > "%LOG%" 2>&1
type "%LOG%"
echo.
echo A saida tambem foi gravada em %LOG%
pause
exit /b

:principal
set JAVA_HOME=C:\android\jdk17
set ANDROID_HOME=C:\android\sdk
set PATH=%JAVA_HOME%\bin;%ANDROID_HOME%\platform-tools;%ANDROID_HOME%\cmdline-tools\latest\bin;%PATH%
echo === Java ===
java -version
echo.
echo === adb ===
adb version
echo.
echo === Pacotes do SDK instalados ===
call sdkmanager.bat --sdk_root=%ANDROID_HOME% --list_installed
echo.
echo === Celulares conectados ===
echo ^(o celular precisa estar com depuracao USB ativa e autorizada na tela dele^)
adb devices -l
echo.
echo === Informacoes do celular ===
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
exit /b 0
