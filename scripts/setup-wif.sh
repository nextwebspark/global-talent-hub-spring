#!/usr/bin/env bash
#
# One-time setup: keyless GitHub Actions -> GCP auth via Workload Identity Federation.
# Run once by a project owner. Idempotent where practical (re-running is safe).
#
# After it finishes, set the two printed values as GitHub Actions repository
# VARIABLES (not secrets): GCP_WIF_PROVIDER and GCP_DEPLOY_SA.
#
# Usage: scripts/setup-wif.sh
set -euo pipefail

# ── Config ────────────────────────────────────────────────────────────────────
PROJECT="hak-talent-mapping"
PROJECT_NUMBER="586609281886"
GITHUB_REPO="nextwebspark/global-talent-hub-spring"   # owner/repo

POOL="github-pool"
PROVIDER="github-provider"
DEPLOY_SA="github-deployer"
DEPLOY_SA_EMAIL="${DEPLOY_SA}@${PROJECT}.iam.gserviceaccount.com"

# Runtime SA the Cloud Run service runs as; the deployer must be able to act as it.
RUNTIME_SA="${PROJECT_NUMBER}-compute@developer.gserviceaccount.com"

echo "==> Project: ${PROJECT} (#${PROJECT_NUMBER})  Repo: ${GITHUB_REPO}"

# ── 1. Deploy service account ─────────────────────────────────────────────────
if ! gcloud iam service-accounts describe "${DEPLOY_SA_EMAIL}" --project "${PROJECT}" >/dev/null 2>&1; then
  echo "==> Creating deploy SA ${DEPLOY_SA_EMAIL}"
  gcloud iam service-accounts create "${DEPLOY_SA}" \
    --project "${PROJECT}" \
    --display-name "GitHub Actions deployer (Cloud Run)"
else
  echo "==> Deploy SA already exists"
fi

# ── 2. Grant deploy roles to the deploy SA ────────────────────────────────────
# run.admin: create/update Cloud Run services. cloudbuild.builds.editor: --source build.
# artifactregistry.writer: push the built image. storage.admin: source upload bucket.
echo "==> Granting deploy roles"
for ROLE in \
  roles/run.admin \
  roles/cloudbuild.builds.editor \
  roles/artifactregistry.writer \
  roles/storage.admin; do
  gcloud projects add-iam-policy-binding "${PROJECT}" \
    --member="serviceAccount:${DEPLOY_SA_EMAIL}" \
    --role="${ROLE}" --condition=None >/dev/null
done

# Let the deployer act as the runtime SA (required to deploy a service that runs as it).
gcloud iam service-accounts add-iam-policy-binding "${RUNTIME_SA}" \
  --project "${PROJECT}" \
  --member="serviceAccount:${DEPLOY_SA_EMAIL}" \
  --role="roles/iam.serviceAccountUser" >/dev/null

# ── 3. Workload Identity pool + OIDC provider ─────────────────────────────────
if ! gcloud iam workload-identity-pools describe "${POOL}" \
      --project "${PROJECT}" --location=global >/dev/null 2>&1; then
  echo "==> Creating WIF pool ${POOL}"
  gcloud iam workload-identity-pools create "${POOL}" \
    --project "${PROJECT}" --location=global \
    --display-name="GitHub Actions pool"
else
  echo "==> WIF pool already exists"
fi

if ! gcloud iam workload-identity-pools providers describe "${PROVIDER}" \
      --project "${PROJECT}" --location=global --workload-identity-pool="${POOL}" >/dev/null 2>&1; then
  echo "==> Creating OIDC provider ${PROVIDER} (restricted to repo ${GITHUB_REPO})"
  gcloud iam workload-identity-pools providers create-oidc "${PROVIDER}" \
    --project "${PROJECT}" --location=global --workload-identity-pool="${POOL}" \
    --display-name="GitHub OIDC" \
    --issuer-uri="https://token.actions.githubusercontent.com" \
    --attribute-mapping="google.subject=assertion.sub,attribute.repository=assertion.repository" \
    --attribute-condition="assertion.repository == '${GITHUB_REPO}'"
else
  echo "==> OIDC provider already exists"
fi

# ── 4. Allow the repo's WIF principalSet to impersonate the deploy SA ──────────
echo "==> Binding repo principalSet -> deploy SA (workloadIdentityUser)"
PRINCIPAL_SET="principalSet://iam.googleapis.com/projects/${PROJECT_NUMBER}/locations/global/workloadIdentityPools/${POOL}/attribute.repository/${GITHUB_REPO}"
gcloud iam service-accounts add-iam-policy-binding "${DEPLOY_SA_EMAIL}" \
  --project "${PROJECT}" \
  --member="${PRINCIPAL_SET}" \
  --role="roles/iam.workloadIdentityUser" >/dev/null

# ── 5. Output the two values for GitHub ───────────────────────────────────────
PROVIDER_RESOURCE="projects/${PROJECT_NUMBER}/locations/global/workloadIdentityPools/${POOL}/providers/${PROVIDER}"
cat <<EOF

==============================================================================
 Done. Set these as GitHub Actions repository VARIABLES (Settings ->
 Secrets and variables -> Actions -> Variables tab):

   GCP_WIF_PROVIDER = ${PROVIDER_RESOURCE}
   GCP_DEPLOY_SA    = ${DEPLOY_SA_EMAIL}

 Also set MAPBOX_ACCESS_TOKEN (public pk.* token) as a repo variable.

 (Variables, not Secrets — none of these are sensitive. No JSON key is created.)
==============================================================================
EOF
