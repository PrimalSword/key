# Kement Print Cam — Android 0.3 lab

Aplicativo-laboratório para usar a **EKEN-Z26/Kement do proprietário** como câmera dedicada da impressora 3D.

## Instalar no Android

A forma mais simples é baixar o APK pronto em **Releases** deste repositório. O GitHub Actions recompila o aplicativo automaticamente e publica:

`KementPrintCam-0.3.0-lab.apk`

No Android, autorize temporariamente a instalação de apps da fonte usada para abrir o APK e instale normalmente.

## O que mudou na 0.3

O teste da 0.2 mostrou uma condição importante: o app oficial Kement recebia o toque, enquanto o Print Cam às vezes ficava conectado ao command server sem receber `preview-start`.

A 0.3 adiciona um segundo caminho independente:

1. faz `app-login` no command server;
2. mantém o VCTP2P pronto;
3. consulta `devices-state` a cada 1 segundo;
4. quando o botão físico acorda a Z26 e o SN aparece online, envia `stream-start` mesmo que `preview-start` tenha sido perdido;
5. tenta a conexão VCTP2P e mantém as retentativas da 0.2.

Assim o fluxo não depende exclusivamente da notificação assíncrona `preview-start`.

## Fluxo de uso

1. Abra **Kement Print Cam**.
2. Informe o e-mail e a senha da conta Kement.
3. Toque em **CONECTAR / AGUARDAR TOQUE**.
4. Espere aparecer `Pronto. Aperte UMA vez o botão da Kement.`
5. Aperte fisicamente o botão da campainha uma vez.
6. Observe o log. Na 0.3 os marcos mais importantes são:
   - `app-login ACK`;
   - `Fallback ativo: consultando devices-state a cada 1 s.`;
   - `devices-state: campainha apareceu ONLINE`;
   - `stream-start resposta | err=0`;
   - `P2P conectado`;
   - contador de vídeo aumentando;
   - `VÍDEO AO VIVO`.

## Build automático

Cada push para `main` executa `.github/workflows/android-apk.yml`.

O pipeline:

- usa JDK 17;
- usa Gradle 8.9;
- restaura automaticamente `libVCTP2P.so` e `libc++_shared.so` a partir dos arquivos texto em `native-b64/`;
- compila `:app:assembleDebug`;
- publica o APK como artifact do GitHub Actions;
- publica/atualiza a Release `v0.3.0-lab` com o APK instalável.

## Build local

Abra a raiz do projeto no Android Studio.

Configuração:

- Android Gradle Plugin 8.7.3;
- Gradle 8.9;
- compileSdk 35;
- JDK 17;
- minSdk 26;
- ABI: `arm64-v8a`.

O task `restoreNativeLibs` roda automaticamente antes de `preBuild`, então as bibliotecas `.so` não precisam ficar diretamente versionadas no Git.

## Segurança

- a senha não é persistida;
- somente o e-mail é lembrado localmente;
- REST usa HTTPS;
- command channel usa TLS quando anunciado pelo backend;
- nenhum token de sessão ou senha está embutido no repositório;
- o projeto destina-se ao dispositivo e à conta do próprio proprietário.
