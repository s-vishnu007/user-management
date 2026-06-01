# samples/

Drop-zone for runtime artifacts the sample stack consumes. **Nothing in this directory is committed** beyond this README and `.gitkeep`; anything you place here is your own license material.

## Expected files

| File | Required | Source |
|---|---|---|
| `license.lic` | for protected endpoints | Downloaded from the control panel after a subscription is issued. |
| `jwks.json` | optional | Override copy of the control panel's public JWKS, if you don't want to bake it into the image. |

## Workflow

1. Boot the control panel (`control-panel-api` + `admin-ui`) locally or against a real environment.
2. Create an organization, attach a subscription with the permissions you want to test.
3. From the subscription detail page, click **Issue License**. Save the downloaded file here as `license.lic`.
4. Run the sample app via Docker Compose (or `docker run`) with this directory mounted at `/etc/app`:

   ```bash
   docker run --rm -p 9090:9090 \
     -v "$(pwd)/samples:/etc/app:ro" \
     -e LICENSE_PATH=/etc/app/license.lic \
     -e LICENSE_STRICT=true \
     sample-docker-app:dev
   ```

5. Hit `http://localhost:9090/api/license/status` to confirm the verifier accepted the file.

## Generating a test license without the control panel

For local-only experiments you can hand-roll a license:

1. Generate an Ed25519 keypair (e.g. `openssl genpkey -algorithm ED25519`).
2. Build the JWT with the claim shape documented in [`../docs/license-format.md`](../docs/license-format.md).
3. Wrap it in the `.lic` envelope (also in that doc).
4. Export the public key as a JWK and drop it into `samples/jwks.json` *or* `sample-docker-app/src/main/resources/jwks.json` before running.

A scripted version of the above will land alongside the control-panel CLI; until then this manual path is enough for ad-hoc testing.
