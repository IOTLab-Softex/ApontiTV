# ADB remoto com VPN

Para uma TV fora da rede local, nao use ADB exposto diretamente na internet. Use VPN.

## Modelo recomendado

- Servidor Aponti TV entra na VPN.
- TV Android entra na mesma VPN.
- A TV continua com ADB na porta `5555`, mas acessivel apenas pelo IP da VPN.
- No cadastro da TV no Aponti TV:
  - `IP da TV`: pode continuar sendo o IP local da TV, quando existir.
  - `IP ADB/VPN da TV`: coloque o IP da VPN da TV.
  - `Porta ADB`: normalmente `5555`.

Quando `IP ADB/VPN da TV` estiver preenchido, todos os comandos ADB usam esse endereco.

## Tailscale

Opcao mais simples.

1. Instale Tailscale no Windows do servidor.
2. Instale Tailscale na TV Android ou no roteador da rede remota.
3. Entre na mesma conta/tailnet.
4. Veja o IP da TV no Tailscale, normalmente `100.x.x.x`.
5. No Aponti TV, preencha `IP ADB/VPN da TV` com esse IP.
6. Teste no servidor:

```powershell
adb connect 100.x.x.x:5555
adb -s 100.x.x.x:5555 get-state
```

## WireGuard

Opcao sem dependencia de conta externa, mas exige configuracao manual.

1. Configure o Windows servidor como peer WireGuard.
2. Configure a TV ou roteador remoto como outro peer.
3. Garanta que o servidor consiga pingar o IP WireGuard da TV.
4. Preencha `IP ADB/VPN da TV` com o IP WireGuard da TV.
5. Teste:

```powershell
adb connect 10.77.0.2:5555
adb -s 10.77.0.2:5555 get-state
```

## Observacao importante

O app Aponti TV nao consegue, sozinho, abrir uma VPN silenciosamente sem permissao do Android. Para uma VPN embutida no app seria necessario implementar `VpnService`, pedir autorizacao na TV e manter um servico de VPN rodando. Para producao, Tailscale ou WireGuard instalado no sistema/roteador e o caminho mais estavel.
