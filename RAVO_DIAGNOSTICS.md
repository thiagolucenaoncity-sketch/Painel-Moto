# RAVO — diagnóstico local (opt-in)

A instrumentação de diagnóstico é experimental. Nas configurações do RAVO, o usuário pode ativar/desativar a coleta, compartilhar o relatório em formato texto ou apagar os registros.

## Privacidade
- **Desligada por padrão.**
- Dados guardados **somente no armazenamento privado do app**, sem upload automático.
- Compartilhamento só ocorre após ação explícita de compartilhar no Android.
- Não registra latitude/longitude, trajetos, endereço, nome de rua, favoritos, velocidade nem identidade do usuário.
- Ao ativar registra o tipo de aparelho e SDK Android, eventos de abertura/fechamento das sessões, amostras de memória e atrasos na thread de interface (a cada 15 s), faixas de precisão do GPS e intervalo entre eventos amostrados, tipo de exceção e classe do primeiro quadro da pilha ao ocorrer crash.
- O indicador **ui_delay_ms** é atraso de agendamento da thread principal, **não é FPS**.
- Os dados são limitados em tamanho (~512 KiB com retenção aproximada de 300 eventos quando excede o teto).
- Os logs de crash existentes no RAVO (`crash` preferences) continuam separados deste diagnóstico.

## Primeiro teste
1. Instalar um APK compilado após esta alteração.
2. Em **Configurações**, ativar **Diagnóstico**.
3. Rodar o RAVO Original em 2D e depois abrir **RAVO Maps Lab** e alternar **Open / Core**; para comparação, testar cada motor por alguns minutos.
4. Voltar às **Configurações** e usar **Compartilhar relatório de diagnóstico**.
5. Conferir o conteúdo antes de enviar. Use **Apagar dados de diagnóstico** quando desejar.

## Observações
Instrumentação não equivale a análise automática pelo ChatGPT; para análise nesta conversa o usuário precisa compartilhar o relatório. Testes de estabilidade e compilação no Android Studio ainda são necessários.
