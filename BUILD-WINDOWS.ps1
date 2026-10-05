$ErrorActionPreference = "Stop"

Write-Host "=== ORBI OmniVoice Android Edge Lab ===" -ForegroundColor Cyan

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    Write-Host "ADB no está en PATH. Abre el proyecto con Android Studio y usa su SDK." -ForegroundColor Yellow
}

if (Get-Command gradle -ErrorAction SilentlyContinue) {
    gradle :app:assembleDebug --stacktrace
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    Write-Host "APK: app\build\outputs\apk\debug\app-debug.apk" -ForegroundColor Green
} else {
    Write-Host "Gradle CLI no está en PATH." -ForegroundColor Yellow
    Write-Host "Opción A: abrir este proyecto en Android Studio 2026 y Build > Build APK(s)."
    Write-Host "Opción B: subir a GitHub; .github/workflows/android-debug.yml compila el APK."
}
