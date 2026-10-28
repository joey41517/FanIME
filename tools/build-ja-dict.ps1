# 把 Mozc OSS 詞庫（src/data/dictionary_oss/dictionary0*.txt，格式：讀音 左ID 右ID 成本 寫法）
# 轉成 FanIME 用的精簡二進位格式 app/src/main/assets/japanese/dict.bin：
#   "JPD1" | int N | int charCount | char[charCount] (UTF-16LE)
#   int[N] 讀音起點 | byte[N] 讀音長度 | int[N] 寫法起點 | byte[N] 寫法長度 | short[N] 成本
# 依讀音（ordinal）排序，同讀音依成本排序；同讀音共用字元資料。
# 只保留成本 < MaxCost，同一組「讀音+寫法」取最低成本。
#
# 用法：.\tools\build-ja-dict.ps1 -SrcDir <mozc dictionary_oss 資料夾> -OutFile app\src\main\assets\japanese\dict.bin
param(
    [Parameter(Mandatory)] [string]$SrcDir,
    [Parameter(Mandatory)] [string]$OutFile,
    [int]$MaxCost = 7000
)

Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.IO;
using System.Text;

public static class JaDictBuilder {
    public static string Build(string[] files, string outFile, int maxCost) {
        var best = new Dictionary<string, int>(1200000, StringComparer.Ordinal);
        foreach (var f in files) {
            foreach (var line in File.ReadLines(f, Encoding.UTF8)) {
                var p = line.Split('\t');
                if (p.Length < 5) continue;
                int c = int.Parse(p[3]);
                if (c >= maxCost || p[0].Length > 255 || p[4].Length > 255) continue;
                string k = p[0] + "\t" + p[4];
                int old;
                if (!best.TryGetValue(k, out old) || c < old) best[k] = c;
            }
        }
        var items = new List<Tuple<string, string, int>>(best.Count);
        foreach (var kv in best) {
            int t = kv.Key.IndexOf('\t');
            items.Add(Tuple.Create(kv.Key.Substring(0, t), kv.Key.Substring(t + 1), kv.Value));
        }
        items.Sort((a, b) => {
            int r = string.CompareOrdinal(a.Item1, b.Item1);
            return r != 0 ? r : a.Item3.CompareTo(b.Item3);
        });

        int n = items.Count;
        var chars = new StringBuilder();
        var rOff = new int[n]; var rLen = new byte[n]; var sOff = new int[n]; var sLen = new byte[n]; var cost = new short[n];
        string prevR = null; int prevOff = 0;
        for (int i = 0; i < n; i++) {
            var it = items[i];
            if (it.Item1 != prevR) { prevOff = chars.Length; chars.Append(it.Item1); prevR = it.Item1; }
            rOff[i] = prevOff; rLen[i] = (byte)it.Item1.Length;
            sOff[i] = chars.Length; chars.Append(it.Item2); sLen[i] = (byte)it.Item2.Length;
            cost[i] = (short)it.Item3;
        }
        using (var bw = new BinaryWriter(File.Create(outFile))) {
            bw.Write(Encoding.ASCII.GetBytes("JPD1"));
            bw.Write(n);
            bw.Write(chars.Length);
            bw.Write(Encoding.Unicode.GetBytes(chars.ToString()));
            foreach (var v in rOff) bw.Write(v);
            bw.Write(rLen);
            foreach (var v in sOff) bw.Write(v);
            bw.Write(sLen);
            foreach (var v in cost) bw.Write(v);
        }
        return string.Format("entries: {0}, chars: {1}", n, chars.Length);
    }
}
'@

$files = (Get-ChildItem (Join-Path $SrcDir 'dictionary0*.txt') | Sort-Object Name).FullName
$out = [IO.Path]::GetFullPath($OutFile)
[JaDictBuilder]::Build($files, $out, $MaxCost)
"file: {0:N0} KB" -f ((Get-Item $out).Length / 1KB)
