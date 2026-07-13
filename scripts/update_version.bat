@echo off
chcp 65001 >nul 2>&1
REM ============================================================
REM update_version.bat — 统一版本号同步脚本 (Windows)
REM 从 version.properties 读取版本号，同步到所有配置文件
REM 用法: scripts\update_version.bat
REM ============================================================
setlocal enabledelayedexpansion

set "ROOT_DIR=%~dp0.."
set "PROPS_FILE=%ROOT_DIR%\version.properties"
set "IOS_YML=%ROOT_DIR%\ios\project.yml"

REM ---------- 读取 version.properties ----------
if not exist "%PROPS_FILE%" (
    echo 错误: 找不到 %PROPS_FILE%
    exit /b 1
)

set "versionName="
set "versionCode="
set "iosBuildNumber="

for /f "usebackq tokens=1,2 delims==" %%a in ("%PROPS_FILE%") do (
    set "line=%%a"
    if not "!line:~0,1!"=="#" (
        if "%%a"=="versionName" set "versionName=%%b"
        if "%%a"=="versionCode" set "versionCode=%%b"
        if "%%a"=="iosBuildNumber" set "iosBuildNumber=%%b"
    )
)

if "%versionName%"=="" (
    echo 错误: version.properties 缺少 versionName
    exit /b 1
)

echo 当前版本号:
echo   versionName    = %versionName%
echo   versionCode    = %versionCode%
echo   iosBuildNumber = %iosBuildNumber%

REM ---------- Android ----------
echo ✓ Android build.gradle.kts — 已直接读取 version.properties，无需同步

REM ---------- iOS ----------
if not exist "%IOS_YML%" (
    echo 警告: 找不到 %IOS_YML%，跳过 iOS 版本同步
) else (
    REM 使用 PowerShell 进行替换
    powershell -Command "(Get-Content '%IOS_YML%') -replace 'MARKETING_VERSION: .*', 'MARKETING_VERSION: \"%versionName%\"' -replace 'CURRENT_PROJECT_VERSION: .*', 'CURRENT_PROJECT_VERSION: \"%iosBuildNumber%\"' | Set-Content '%IOS_YML%'"
    echo ✓ iOS project.yml — 已更新为 %versionName% (%iosBuildNumber%)
)

echo.
echo 同步完成！所有版本号已统一为 %versionName%
endlocal
