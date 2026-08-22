#!/usr/bin/env bash
# Runs the whole demo end to end, in the order the video walkthrough uses it.
# Safe to re-run: each script skips or no-ops around state that already exists.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

for script in 00_bootstrap_admin.sh 01_provision_apps.sh 02_financials_create_coa.sh \
              03_hrms_post_payroll.sh 04_business_post_entries.sh 05_vendor_post_entries.sh \
              06_accountant_manual_entries.sh 07_reports.sh 08_verify_audit.sh; do
  echo "==================== $script ===================="
  ./"$script"
  echo
done
