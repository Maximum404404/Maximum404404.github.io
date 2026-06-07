# Regenerates radio-tracks.js from the /Songs and /DJI folders, INCLUDING each
# track's real duration (read from the file via Windows). Accurate durations let
# the radio build an exact wall-clock timeline, which is what keeps every
# listener's device in sync.
#
# Re-run after adding or removing tracks (or just double-click RefreshRadio.exe):
#     powershell -ExecutionPolicy Bypass -File build-radio.ps1

$ErrorActionPreference = 'Stop'
$root  = if ($PSScriptRoot) { $PSScriptRoot } else { (Get-Location).Path }
$audio = '\.(webm|mp3|ogg|wav|m4a|opus|aac|flac)$'
$shell = New-Object -ComObject Shell.Application

# "HH:MM:SS" (with stray unicode marks) -> whole seconds
function Parse-Dur($s) {
    if (-not $s) { return 0 }
    $clean = ($s -replace '[^0-9:]', '')
    if (-not $clean) { return 0 }
    $sec = 0
    foreach ($p in $clean.Split(':')) { if ($p -ne '') { $sec = $sec * 60 + [int]$p } }
    return $sec
}

function Get-Tracks($dir, $type) {
    $path = Join-Path $root $dir
    if (-not (Test-Path $path)) { return @() }
    $folder = $shell.Namespace($path)

    # Find the "Length" column index once (usually 27, but confirm per system)
    $lenIdx = 27
    for ($i = 0; $i -lt 350; $i++) { if ($folder.GetDetailsOf($null, $i) -eq 'Length') { $lenIdx = $i; break } }

    Get-ChildItem -Path $path -File |
        Where-Object { $_.Name -match $audio } |
        Sort-Object Name |
        ForEach-Object {
            $title = [IO.Path]::GetFileNameWithoutExtension($_.Name) -replace '\s*\[[^\]]+\]\s*$', ''
            $item  = $folder.ParseName($_.Name)
            $sec   = if ($item) { Parse-Dur ($folder.GetDetailsOf($item, $lenIdx)) } else { 0 }
            [pscustomobject]@{ title = $title.Trim(); src = "$dir/$($_.Name)"; type = $type; sec = $sec }
        }
}

$songs  = @(Get-Tracks 'Songs' 'song')
$idents = @(Get-Tracks 'DJI'   'ident')

$manifest = [pscustomobject]@{ songs = $songs; idents = $idents }
$json = $manifest | ConvertTo-Json -Depth 6
$js   = "window.RADIO_MANIFEST = $json;`n"

$out = Join-Path $root 'radio-tracks.js'
[IO.File]::WriteAllText($out, $js, (New-Object System.Text.UTF8Encoding($false)))

$noDur = @($songs + $idents | Where-Object { $_.sec -le 0 }).Count
Write-Output ("radio-tracks.js written: {0} songs, {1} idents ({2} with no readable duration)" -f $songs.Count, $idents.Count, $noDur)
