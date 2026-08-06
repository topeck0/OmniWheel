using System;
using System.Collections.Generic;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace OmniWheelPC.UI;

public class HudWidget
{
    [JsonPropertyName("id")]
    public string Id { get; set; } = "";

    [JsonPropertyName("label")]
    public string Label { get; set; } = "";

    [JsonPropertyName("cx")]
    public float Cx { get; set; }

    [JsonPropertyName("cy")]
    public float Cy { get; set; }

    [JsonPropertyName("wFrac")]
    public float WFrac { get; set; }

    [JsonPropertyName("hFrac")]
    public float HFrac { get; set; }

    [JsonPropertyName("vJoyBtn")]
    public int VJoyBtn { get; set; }

    [JsonPropertyName("scale")]
    public float Scale { get; set; } = 1.0f;

    [JsonPropertyName("circular")]
    public bool IsCircular { get; set; }

    [JsonPropertyName("steering")]
    public bool IsSteering { get; set; }

    [JsonPropertyName("pedal")]
    public bool IsPedal { get; set; }
}

public static class HudLayoutManager
{
    public const string DefaultLayoutJson = @"[
  {""id"":""steering"",""label"":""Steering"",""cx"":0.17067544,""cy"":0.5969476,""wFrac"":0.32,""hFrac"":0.62,""vJoyBtn"":0,""scale"":1,""circular"":true,""steering"":true,""pedal"":false},
  {""id"":""clutch"",""label"":""CLUTCH"",""cx"":0.18843506,""cy"":0.5793878,""wFrac"":0.125,""hFrac"":0.58518517,""vJoyBtn"":0,""scale"":1,""circular"":false,""steering"":false,""pedal"":true},
  {""id"":""brake"",""label"":""BRAKE"",""cx"":0.79410994,""cy"":0.5470944,""wFrac"":0.09857856,""hFrac"":0.6645447,""vJoyBtn"":0,""scale"":0.9982368,""circular"":false,""steering"":false,""pedal"":true},
  {""id"":""gas"",""label"":""GAS"",""cx"":0.91070157,""cy"":0.5510004,""wFrac"":0.09857856,""hFrac"":0.6645447,""vJoyBtn"":0,""scale"":1,""circular"":false,""steering"":false,""pedal"":true},
  {""id"":""btn_1"",""label"":""9"",""cx"":0.90572745,""cy"":0.06188174,""wFrac"":0.13854167,""hFrac"":0.24444445,""vJoyBtn"":9,""scale"":1,""circular"":false,""steering"":false,""pedal"":false},
  {""id"":""btn_2"",""label"":""10"",""cx"":0.74909747,""cy"":0.06395936,""wFrac"":0.13541667,""hFrac"":0.2574074,""vJoyBtn"":10,""scale"":1,""circular"":false,""steering"":false,""pedal"":false},
  {""id"":""btn_3"",""label"":""11"",""cx"":0.60399806,""cy"":0.08702586,""wFrac"":0.109375,""hFrac"":0.22037037,""vJoyBtn"":11,""scale"":1,""circular"":false,""steering"":false,""pedal"":false},
  {""id"":""btn_4"",""label"":""4"",""cx"":0.035183467,""cy"":0.3200492,""wFrac"":0.078125,""hFrac"":0.15555556,""vJoyBtn"":4,""scale"":1,""circular"":false,""steering"":false,""pedal"":false},
  {""id"":""btn_5"",""label"":""5"",""cx"":0.31635338,""cy"":0.3284545,""wFrac"":0.078125,""hFrac"":0.15555556,""vJoyBtn"":5,""scale"":1,""circular"":false,""steering"":false,""pedal"":false},
  {""id"":""btn_6"",""label"":""6"",""cx"":0.09714611,""cy"":0.16718335,""wFrac"":0.078125,""hFrac"":0.15555556,""vJoyBtn"":6,""scale"":1,""circular"":false,""steering"":false,""pedal"":false},
  {""id"":""btn_7"",""label"":""7"",""cx"":0.17963584,""cy"":0.1644227,""wFrac"":0.078125,""hFrac"":0.15555556,""vJoyBtn"":7,""scale"":1,""circular"":false,""steering"":false,""pedal"":false},
  {""id"":""btn_8"",""label"":""8"",""cx"":0.26276338,""cy"":0.16724399,""wFrac"":0.078125,""hFrac"":0.15555556,""vJoyBtn"":8,""scale"":1,""circular"":false,""steering"":false,""pedal"":false},
  {""id"":""btn_9"",""label"":""1"",""cx"":0.6553623,""cy"":0.6197505,""wFrac"":0.104166664,""hFrac"":0.18518518,""vJoyBtn"":1,""scale"":1,""circular"":false,""steering"":false,""pedal"":false},
  {""id"":""btn_10"",""label"":""2"",""cx"":0.57093632,""cy"":0.42049637,""wFrac"":0.078125,""hFrac"":0.15555556,""vJoyBtn"":2,""scale"":1,""circular"":false,""steering"":false,""pedal"":false},
  {""id"":""btn_11"",""label"":""3"",""cx"":0.6612059,""cy"":0.84482443,""wFrac"":0.078125,""hFrac"":0.15555556,""vJoyBtn"":3,""scale"":1,""circular"":false,""steering"":false,""pedal"":false},
  {""id"":""btn_12"",""label"":""12"",""cx"":0.03248721,""cy"":0.9145924,""wFrac"":0.05,""hFrac"":0.10555556,""vJoyBtn"":12,""scale"":1,""circular"":false,""steering"":false,""pedal"":false},
  {""id"":""btn_13"",""label"":""14"",""cx"":0.428841,""cy"":0.7017918,""wFrac"":0.08541667,""hFrac"":0.15555556,""vJoyBtn"":14,""scale"":1,""circular"":false,""steering"":false,""pedal"":false},
  {""id"":""btn_14"",""label"":""15"",""cx"":0.51983815,""cy"":0.7007276,""wFrac"":0.09166667,""hFrac"":0.15555556,""vJoyBtn"":15,""scale"":1,""circular"":false,""steering"":false,""pedal"":false},
  {""id"":""btn_15"",""label"":""16"",""cx"":0.47403532,""cy"":0.8625309,""wFrac"":0.18125,""hFrac"":0.15555556,""vJoyBtn"":16,""scale"":1,""circular"":false,""steering"":false,""pedal"":false},
  {""id"":""btn_16"",""label"":""13"",""cx"":0.01573236,""cy"":0.7715346,""wFrac"":0.078125,""hFrac"":0.15555556,""vJoyBtn"":13,""scale"":1,""circular"":false,""steering"":false,""pedal"":false}
]";

    public static List<HudWidget> LoadLayout(string? json)
    {
        if (string.IsNullOrWhiteSpace(json))
            json = DefaultLayoutJson;

        try
        {
            var options = new JsonSerializerOptions { PropertyNameCaseInsensitive = true };
            var list = JsonSerializer.Deserialize<List<HudWidget>>(json, options);
            if (list != null && list.Count > 0)
                return list;
        }
        catch { }

        return JsonSerializer.Deserialize<List<HudWidget>>(DefaultLayoutJson)!;
    }

    /// <summary>Parse a single-widget JSON object sent live from the phone.</summary>
    public static HudWidget? ParseSingleWidget(string json)
    {
        try
        {
            var options = new JsonSerializerOptions { PropertyNameCaseInsensitive = true };
            return JsonSerializer.Deserialize<HudWidget>(json, options);
        }
        catch
        {
            return null;
        }
    }
}
