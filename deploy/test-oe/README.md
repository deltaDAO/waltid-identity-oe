# OE test wallet (wallet.test-oe.delta-dao.com)

Wallet API and dev wallet frontend from this repository, run as their own Docker Compose project
(`wallet-test-oe`) next to other wallets on the same server.

| Container | Image | Host port |
|---|---|---|
| `wallet-api-oe` | `deltadao/wallet-api:oe-wallet-signup` | `127.0.0.1:7011` |
| `dev-wallet-oe` | `deltadao/waltid-dev-wallet:oe-wallet-signup` | `127.0.0.1:7114` |

Data lives in the Docker volume `wallet-test-oe_wallet-oe-data` (SQLite), separate from other wallets.
nginx routes `/wallet-api/` to the wallet API and everything else to the frontend.

## Setup

Run from this directory (`deploy/test-oe`).

1. Wallet API config (server-local, git-ignored):

   ```bash
   cp -r ../../waltid-services/waltid-wallet-api/config wallet-api-config
   nano wallet-api-config/ktor-authnz.conf   # set your own pepper, signingKey and verificationKey
   ```

   The copied config enables `ktor-authnz` with email and web3 login (`ok: true`, as this version expects).

2. Build and start:

   ```bash
   docker compose build
   docker compose up -d
   docker compose logs -f --tail=100 wallet-api-oe
   ```

3. nginx and HTTPS (DNS A record `wallet.test-oe.delta-dao.com` must point to this server first):

   ```bash
   sudo cp nginx/wallet.test-oe.delta-dao.com.conf /etc/nginx/sites-available/
   sudo ln -s /etc/nginx/sites-available/wallet.test-oe.delta-dao.com.conf /etc/nginx/sites-enabled/
   sudo nginx -t && sudo systemctl reload nginx
   sudo certbot --nginx -d wallet.test-oe.delta-dao.com --redirect
   ```

## Update to a newer commit

```bash
git pull
docker compose build
docker compose up -d
```

## Stop or remove

```bash
docker compose down        # keeps the database volume
docker compose down -v     # also deletes the database
```
