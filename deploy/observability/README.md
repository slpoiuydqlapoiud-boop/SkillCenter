# Skill Center Observability Deployment Templates

These files are deployment templates for the internal Prometheus, Grafana and Alertmanager stack.

1. Put the same value configured as `skill-center.operations.metrics-token` in `secrets/skill-center-metrics-token`.
2. Mount `prometheus.yml` and `skill-center-alerts.yml` into Prometheus.
3. Import `grafana-skill-center-dashboard.json` into the internal Grafana instance.
4. Replace the Alertmanager webhook URL with the approved department notification gateway.
5. Verify network allowlists and TLS before enabling production scraping.

The application endpoint accepts both the existing `X-Metrics-Token` header and the standard `Authorization: Bearer <token>` form. These templates do not contain credentials or production hostnames.
