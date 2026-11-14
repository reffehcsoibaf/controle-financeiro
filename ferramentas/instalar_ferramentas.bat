@echo off
setlocal
chcp 65001 >nul
if not exist C:\logs mkdir C:\logs
set LOG=C:\logs\instalar_android.txt
set BASE=C:\android
set JDKDIR=%BASE%\jdk17
set SDKDIR=%BASE%\sdk
set TAR=%SystemRoot%\System32\tar.exe
set JAVA_HOME=%JDKDIR%
set ANDROID_HOME=%SDKDIR%
set ANDROID_SDK_ROOT=%SDKDIR%
set PATH=%JDKDIR%\bin;%SDKDIR%\cmdline-tools\latest\bin;%SDKDIR%\platform-tools;%PATH%

echo === Passo 1 de 3: baixar e extrair o JDK e as ferramentas ===
call :baixar > "%LOG%" 2>&1
type "%LOG%"
if errorlevel 1 (
  echo.
  echo Houve um erro. Veja o texto acima ou o arquivo %LOG%
  pause
  exit /b 1
)

echo.
echo === Passo 2 de 3: licencas do Android ===
echo A seguir aparece o texto da licenca do Android SDK. Leia e responda y para
echo aceitar ou n para recusar. Sem aceitar, a instalacao nao continua.
echo.
call sdkmanager.bat --sdk_root=%SDKDIR% --licenses

echo.
echo === Passo 3 de 3: instalar os pacotes do Android ===
call :pacotes >> "%LOG%" 2>&1
set RESULTADO=%errorlevel%
echo O registro completo esta em %LOG%
if not "%RESULTADO%"=="0" (
  echo.
  echo ERRO na instalacao dos pacotes. Provavelmente a licenca nao foi aceita.
  echo Rode este arquivo de novo.
  pause
  exit /b 1
)

echo.
echo === Concluido. Agora rode verificar.bat ===
pause
exit /b 0

:baixar
if not exist "%BASE%" mkdir "%BASE%"
if not exist "%BASE%\baixados" mkdir "%BASE%\baixados"

echo --- JDK 17 ---
if exist "%JDKDIR%\bin\java.exe" (
  echo JDK ja instalado em %JDKDIR%
) else (
  if exist "%BASE%\baixados\jdk17.zip" (
    echo Usando o JDK ja baixado em %BASE%\baixados\jdk17.zip
  ) else (
    echo Baixando o JDK 17 ^(Eclipse Temurin^)...
    curl.exe -L --fail -s -S -o "%BASE%\baixados\jdk17.part" "https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse"
    if errorlevel 1 ( echo ERRO ao baixar o JDK. & exit /b 1 )
    move "%BASE%\baixados\jdk17.part" "%BASE%\baixados\jdk17.zip" >nul
  )
  if exist "%BASE%\jdk_tmp" rmdir /s /q "%BASE%\jdk_tmp"
  mkdir "%BASE%\jdk_tmp"
  "%TAR%" -xf "%BASE%\baixados\jdk17.zip" -C "%BASE%\jdk_tmp"
  if errorlevel 1 ( echo ERRO ao extrair o JDK. & exit /b 1 )
  for /d %%D in ("%BASE%\jdk_tmp\*") do move "%%D" "%JDKDIR%" >nul
  rmdir /s /q "%BASE%\jdk_tmp"
)
if not exist "%JDKDIR%\bin\java.exe" ( echo ERRO: JDK nao ficou no lugar esperado. & exit /b 1 )

echo --- Ferramentas de linha de comando do Android ---
if exist "%SDKDIR%\cmdline-tools\latest\bin\sdkmanager.bat" (
  echo cmdline-tools ja instaladas
) else (
  if exist "%BASE%\baixados\cmdline-tools.zip" (
    echo Usando as cmdline-tools ja baixadas
  ) else (
    echo Baixando cmdline-tools...
    curl.exe -L --fail -s -S -o "%BASE%\baixados\cmdline-tools.part" "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip"
    if errorlevel 1 ( echo ERRO ao baixar as cmdline-tools. & exit /b 1 )
    move "%BASE%\baixados\cmdline-tools.part" "%BASE%\baixados\cmdline-tools.zip" >nul
  )
  if exist "%BASE%\ct_tmp" rmdir /s /q "%BASE%\ct_tmp"
  mkdir "%BASE%\ct_tmp"
  "%TAR%" -xf "%BASE%\baixados\cmdline-tools.zip" -C "%BASE%\ct_tmp"
  if errorlevel 1 ( echo ERRO ao extrair as cmdline-tools. & exit /b 1 )
  if not exist "%SDKDIR%\cmdline-tools" mkdir "%SDKDIR%\cmdline-tools"
  move "%BASE%\ct_tmp\cmdline-tools" "%SDKDIR%\cmdline-tools\latest" >nul
  rmdir /s /q "%BASE%\ct_tmp"
)
if not exist "%SDKDIR%\cmdline-tools\latest\bin\sdkmanager.bat" ( echo ERRO: cmdline-tools nao ficaram no lugar esperado. & exit /b 1 )
echo Passo 1 concluido.
exit /b 0

:pacotes
echo --- Pacotes do Android ---
call sdkmanager.bat --sdk_root=%SDKDIR% "platform-tools" "platforms;android-35" "build-tools;35.0.0"
if errorlevel 1 ( echo ERRO no sdkmanager. & exit /b 1 )
echo --- Variaveis de ambiente do usuario ---
setx JAVA_HOME "%JDKDIR%" >nul
setx ANDROID_HOME "%SDKDIR%" >nul
setx ANDROID_SDK_ROOT "%SDKDIR%" >nul
echo Variaveis gravadas. Abra um terminal novo para valerem.
exit /b 0
