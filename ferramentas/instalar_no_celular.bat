@echo off
setlocal
chcp 65001 >nul
if not exist C:\logs mkdir C:\logs
set LOG=C:\logs\instalar_no_celular.txt
set RAIZ=%~dp0..
set JAVA_HOME=C:\android\jdk17
set ANDROID_HOME=C:\android\sdk
set PATH=%JAVA_HOME%\bin;%ANDROID_HOME%\platform-tools;%PATH%

echo Compilando e instalando no celular. Isso pode levar alguns minutos.
echo Deixe o celular conectado, com a tela desbloqueada. Se ele perguntar se
echo permite a instalacao, aceite.
call :principal > "%LOG%" 2>&1
set RESULTADO=%errorlevel%
type "%LOG%"
echo.
if "%RESULTADO%"=="0" (
  echo === Pronto. O app foi instalado e aberto no celular. ===
) else (
  echo === Deu erro. Veja o texto acima ou o arquivo %LOG% ===
)
pause
exit /b %RESULTADO%

:principal
cd /d "%RAIZ%"
adb devices -l
call gradlew.bat testDebugUnitTest installDebug
if errorlevel 1 exit /b 1
adb shell am start -n br.com.fabioscheffer.controlefinanceiro/.MainActivity
exit /b %errorlevel%
