# Downloads the sentence-embedding model Even Hand reads answers with (all-MiniLM-L6-v2, 87 MB, Apache-2.0)
# to %USERPROFILE%\.even-hand\models, pinned to the validated revision, and checks every file's SHA-256.
# Run once with internet; Even Hand itself never goes online. Skips files already present and correct.
$ErrorActionPreference = 'Stop'
# Windows PowerShell 5.1 redraws its progress bar per packet, which slows an 87 MB download tenfold.
$ProgressPreference = 'SilentlyContinue'
$repo = 'sentence-transformers/all-MiniLM-L6-v2'
$rev = '1110a243fdf4706b3f48f1d95db1a4f5529b4d41'
$dir = if ($env:EVEN_HAND_MODEL) { $env:EVEN_HAND_MODEL } else { Join-Path $env:USERPROFILE '.even-hand\models\all-MiniLM-L6-v2' }
$files = [ordered]@{
  'config.json'               = '953f9c0d463486b10a6871cc2fd59f223b2c70184f49815e7efbcab5d8908b41'
  'vocab.txt'                 = '07eced375cec144d27c900241f3e339478dec958f92fddbc551f295c992038a3'
  'model.safetensors'         = '53aa51172d142c89d9012cce15ae4d6cc0ca6895895114379cacb4fab128d9db'
  'tokenizer_config.json'     = 'acb92769e8195aabd29b7b2137a9e6d6e25c476a4f15aa4355c233426c61576b'
  'sentence_bert_config.json' = 'fc1993fde0a95c24ec6c022539d41cf6e2f7c9721e5415d6fb6897472a9cd4b7'
  '1_Pooling/config.json'     = '4be450dde3b0273bb9787637cfbd28fe04a7ba6ab9d36ac48e92b11e350ffc23'
}
New-Item -ItemType Directory -Force (Join-Path $dir '1_Pooling') | Out-Null
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
foreach ($f in $files.Keys) {
  $target = Join-Path $dir $f
  if ((Test-Path $target) -and ((Get-FileHash $target -Algorithm SHA256).Hash -eq $files[$f].ToUpper())) { continue }
  Write-Host "Fetching $f ..."
  $part = "$target.part"
  Invoke-WebRequest -UseBasicParsing -Uri "https://huggingface.co/$repo/resolve/$rev/$f" -OutFile $part
  $got = (Get-FileHash $part -Algorithm SHA256).Hash
  if ($got -ne $files[$f].ToUpper()) { Remove-Item $part; throw "SHA-256 mismatch for $f (got $got). Not installed." }
  Move-Item -Force $part $target
}
Write-Host "Embedding model ready in $dir"
