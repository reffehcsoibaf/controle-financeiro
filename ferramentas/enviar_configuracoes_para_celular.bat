@echo off
setlocal
chcp 65001 >nul
if not exist C:\logs mkdir C:\logs
set LOG=C:\logs\enviar_configuracoes.txt
set ANDROID_HOME=C:\android\sdk
set PATH=%ANDROID_HOME%\platform-tools;%PATH%
set ORIGEM=%APPDATA%\ControleFinanceiro\preferencias.json
rem Copia portatil do programa: passe o caminho do preferencias.json da pasta dados como argumento.
if not "%~1"=="" set ORIGEM=%~1

call :principal > "%LOG%" 2>&1
set RESULTADO=%errorlevel%
type "%LOG%"
echo.
if "%RESULTADO%"=="0" (
  echo === Pronto. No celular, abra o app, entre em Mais, Configuracoes e escolha ===
  echo === Importar configuracoes do Windows. O arquivo se chama configuracoes-windows.json, ===
  echo === na pasta Download do armazenamento interno. ===
) else (
  echo === Deu erro. Veja o texto acima ou o arquivo %LOG% ===
)
pause
exit /b %RESULTADO%

:principal
echo Arquivo de configuracoes do Windows: %ORIGEM%
if not exist "%ORIGEM%" (
  echo ERRO: nao encontrei esse arquivo. Abra o programa do Windows ao menos uma vez.
  exit /b 1
)
adb devices -l
adb push "%ORIGEM%" /sdcard/Download/configuracoes-windows.json
if errorlevel 1 exit /b 1
rem Faz o Android indexar o arquivo, para ele aparecer tambem em Recentes e Downloads no seletor.
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Download/configuracoes-windows.json
exit /b 0
