"""Exercise fresh install and populated legacy upgrade against isolated PostgreSQL."""
import argparse
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
DB = "itms_migration_it"
parser = argparse.ArgumentParser()
parser.add_argument("--project", default="itms-verify")
parser.add_argument("--native-wsl", help="Use an already isolated PostgreSQL instance in this WSL distribution")
parser.add_argument("--postgres-port", type=int, default=55432)
args = parser.parse_args()
if not args.project.startswith(("itms-verify", "itms-completion-tests")):
    parser.error("Use a dedicated itms-verify* or itms-completion-tests* project")
if args.native_wsl:
    if not 1024 <= args.postgres_port <= 65535:
        parser.error("postgres port must be between 1024 and 65535")
    base = ["wsl", "-d", args.native_wsl, "--", "psql", "-h", "127.0.0.1", "-p", str(args.postgres_port),
            "-U", "postgres", "-v", "ON_ERROR_STOP=1"]
else:
    base = ["docker", "compose", "-f", str(ROOT / "compose.test.yml"), "-p", args.project,
            "exec", "-T", "postgres", "psql", "-U", "postgres", "-v", "ON_ERROR_STOP=1"]


def sql(statement, database=DB):
    completed = subprocess.run(base + ["-d", database], input=statement,
                               text=True, encoding="utf-8", capture_output=True)
    if completed.returncode:
        raise RuntimeError(completed.stderr)


def reset():
    # The only dropped database is this fixed, test-only name in compose.test.yml.
    sql(f"DROP DATABASE IF EXISTS {DB} WITH (FORCE); CREATE DATABASE {DB};", "postgres")


def migrate():
    for name in ("20261016_traffic_ingestion_receipts.sql", "20261017_project_completion.sql"):
        sql((ROOT / "database/migrations" / name).read_text(encoding="utf-8-sig"))


checks = """
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE indexname='uq_signal_intersection_direction')
     OR NOT EXISTS (SELECT 1 FROM pg_indexes WHERE indexname='uq_alert_active_key')
     OR NOT EXISTS (SELECT 1 FROM pg_indexes WHERE indexname='uq_traffic_data_event_id') THEN
     RAISE EXCEPTION 'Required unique indexes missing';
  END IF;
  IF (SELECT count(*) FROM information_schema.columns WHERE table_name='traffic_data'
      AND column_name IN ('event_id','payload_hash')) <> 2 THEN
     RAISE EXCEPTION 'Event identity columns missing';
  END IF;
END $$;
"""

try:
    reset()
    sql((ROOT / "database/init.sql").read_text(encoding="utf-8-sig"))
    migrate()
    migrate()
    sql(checks)
    sql("DO $$ BEGIN IF EXISTS (SELECT 1 FROM users) THEN RAISE EXCEPTION 'SQL must not seed plaintext users'; END IF; END $$;")
    print("PASS: fresh schema + migration rerun, required indexes, no plaintext seed")

    reset()
    sql((ROOT / "database/testing/legacy-schema.sql").read_text(encoding="utf-8-sig"))
    sql("""
    UPDATE users SET enabled=false, real_name='preserved' WHERE username='manager1';
    INSERT INTO traffic_signals (intersection_id,direction,green_duration,updated_at)
        VALUES ('INT-001','NORTH',77,CURRENT_TIMESTAMP + INTERVAL '1 day');
    INSERT INTO traffic_alerts (title,content,alert_level,intersection_id)
        VALUES ('old','old','WARNING','INT-001');
    INSERT INTO traffic_predictions (intersection_id,prediction_time,model_version,confidence)
        VALUES ('INT-001',CURRENT_TIMESTAMP,'LSTM-v1.0',0.98);
    """)
    migrate()
    migrate()
    sql(checks)
    sql("""
    DO $$ BEGIN
      IF (SELECT count(*) FROM traffic_signals) <> 8
         OR (SELECT green_duration FROM traffic_signals WHERE intersection_id='INT-001' AND direction='NORTH') <> 77
         OR (SELECT count(*) FROM traffic_signal_duplicate_archive) <> 1 THEN
          RAISE EXCEPTION 'Signal deduplication/archive failed';
      END IF;
      IF (SELECT count(*) FROM users) <> 5 OR NOT EXISTS
          (SELECT 1 FROM users WHERE username='manager1' AND enabled=false AND real_name='preserved' AND auth_version=0) THEN
          RAISE EXCEPTION 'Existing users changed';
      END IF;
      IF NOT EXISTS (SELECT 1 FROM traffic_alerts WHERE source='LEGACY' AND active_key IS NULL AND last_seen_at=created_at) THEN
          RAISE EXCEPTION 'Legacy alert metadata lost';
      END IF;
      IF NOT EXISTS (SELECT 1 FROM traffic_predictions WHERE model_version='SIMULATION-legacy' AND confidence IS NULL) THEN
          RAISE EXCEPTION 'Legacy simulation mislabeled';
      END IF;
      BEGIN
          INSERT INTO traffic_signals (intersection_id,direction) VALUES ('INT-001','NORTH');
          RAISE EXCEPTION 'Duplicate signal was accepted';
      EXCEPTION WHEN unique_violation THEN NULL;
      END;
    END $$;
    """)
    print("PASS: populated legacy upgrade + rerun, archived duplicates, preserved users, relabeled simulation")
finally:
    sql(f"DROP DATABASE IF EXISTS {DB} WITH (FORCE);", "postgres")
