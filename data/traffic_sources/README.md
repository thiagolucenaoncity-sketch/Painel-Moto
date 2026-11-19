# Banco Inteligente RAVO — fontes de semáforos de São Paulo

A base estadual embarcada no APK é `app/src/main/assets/roadalerts/sp_semaforos.csv`.
Ela é produzida pela automação `Atualizar semaforos SP` (dias 1 e 16).

## Fontes independentes

- **OpenStreetMap (OSM / ODbL):** cobertura estadual, extraída de nós com
  `highway=traffic_signals` usando o PBF estadual. É a base principal.
- **GeoSampa / CET:** pontos oficiais de cruzamentos semaforizados da **cidade
  de São Paulo**, consultados pelo WFS público quando disponível. Podem
  representar a interseção, não a posição individual de cada equipamento.
- **Outros órgãos:** podem ser acrescentados como CSVs de fontes de fato
  independentes e legalmente reutilizáveis. Não conte cinco sites que copiam
  OSM como cinco confirmações.

## Acrescentar uma fonte

Coloque nesta pasta um arquivo UTF-8, exemplo `prefeitura_abc.csv`:

```csv
id,lat,lon
12345,-23.7000000,-46.6000000
```

Os IDs e as coordenadas devem existir na fonte original; **não invente
registros**. Cada arquivo deve representar uma única fonte auditada. Confira
licença, data, precisão e natureza dos pontos antes da importação. Se a fonte
é derivada do OSM, não a classifique como independente do OSM.

O nome do arquivo (sem `.csv`) vira o identificador da origem; use apenas
letras ASCII, números e `_`. O importador GeoSampa grava automaticamente
`geosampa.csv` nesta pasta quando a consulta municipal funciona.

## Regras de fusão

1. Mantém todos os semáforos distintos da base OSM.
2. Só combina fontes **diferentes**, com pontos a até **12 m** e correspondência
   não ambígua; nunca junta semáforos da mesma fonte por simples proximidade.
3. A média é ponderada (fonte oficial com maior peso), mas a posição consolidada
   desloca no máximo **5 m** do cadastro de referência.
4. Se houver múltiplos semáforos plausíveis no mesmo cruzamento, não escolhe
   arbitrariamente um deles: preserva os pontos sem aproximação artificial.
5. Pontos municipais sem OSM em até **24 m** podem entrar como registros novos,
   identificados pela fonte. Nenhum registro é marcado como fase real do sinal.
6. O processo publica auditoria em
   `data/traffic_quality/sp_semaforos_report.json`.

A fusão ocorre **no GitHub**, não durante o uso do mapa. Os CSVs finais
mantêm o formato `id,lat,lon` consumido pelo Android. Consultas ao
Overpass só são usadas como contingência na extração estadual.

**Atenção:** "confirmado por 2 fontes" não equivale a uma visita ao local.
Nunca use essa base como garantia de presença, funcionamento ou cor de semáforo.

Atribuições: © colaboradores do OpenStreetMap, dados ODbL; GeoSampa/CET —
Prefeitura de São Paulo, quando a segunda fonte for usada.
