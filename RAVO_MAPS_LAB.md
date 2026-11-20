# RAVO Maps Lab — marco inicial

Experimentos isolados do aplicativo RAVO Original.

## Como abrir
No Android, abra **RAVO > Configurações > RAVO Maps Lab · Open / Core (experimental)**.

## Motores
- **RAVO Maps Open:** usa a biblioteca MapLibre já declarada no `app/build.gradle.kts` e o estilo online do OpenFreeMap. Aponta inicialmente para São Paulo. Ainda **não é offline** nem oferece novo motor de rotas.
- **RAVO Maps Core:** usa `android.graphics.Canvas` e gestos nativos do Android. Desenha ruas e um marcador **inteiramente fictícios** para testar zoom, arraste e desempenho. **Não é GPS real. Não usar para navegação.**

## Arquivos
- `app/src/main/java/com/moto/painel/maps/RavoMapsLabActivity.kt`
- `app/src/main/java/com/moto/painel/maps/RavoMapsCoreView.kt`
- Registro da Activity em `app/src/main/AndroidManifest.xml`
- Atalho em `app/src/main/java/com/moto/painel/SettingsActivity.kt`

## Primeiro teste no Galaxy A11
1. Sincronize o projeto no Android Studio e execute a versão atual.
2. Abra as configurações e entre no RAVO Maps Lab.
3. No **Core**, arraste o mapa e faça gesto de pinça. O desenho não representa ruas reais.
4. Alterne para o **Open**, com conexão à internet. O mapa de São Paulo deverá aparecer.
5. Alterne entre os dois, pressione Voltar e reabra o laboratório. Confira se a aplicação continua estável.
6. Se travar, exporte o Logcat com `package:com.moto.painel is:crash`.

## Escopo e próximos passos
Essa etapa estabelece a separação dos motores e permite testes seguros; ela **não promete** desempenho superior nem navegação offline. Próximos passos possíveis: medir FPS/memória, armazenar tiles locais, adicionar dados geográficos reais ao Core, projeção Mercator e GPS, e depois motor de rotas. Nenhum desses itens foi implementado ainda.
