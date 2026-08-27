"""Testes do catálogo e do cálculo de progresso dos planos."""

from pathlib import Path
import unittest

from biblia.database import BibleDatabase
from biblia.plans import build_plan_catalog, progress_summary


DB_PATH = Path(__file__).resolve().parents[1] / "data" / "biblia.db"


class ReadingPlanTests(unittest.TestCase):
    """Valida quantidade mínima, duração e progresso determinístico."""

    def setUp(self):
        """Abre o banco bíblico distribuído para gerar os catálogos."""
        self.database = BibleDatabase(DB_PATH)

    def tearDown(self):
        """Libera a conexão usada por cada cenário."""
        self.database.close()

    def test_catalog_contains_all_requested_plans(self):
        """Confere nomes, quantidade mínima e durações principais."""
        catalog = build_plan_catalog(self.database, "bpm")
        self.assertGreaterEqual(len(catalog), 17)
        self.assertEqual(365, catalog["bible_365"].days)
        self.assertEqual(31, catalog["proverbs_31"].days)
        self.assertTrue(all(plan.readings for plan in catalog.values()))

    def test_progress_summary(self):
        """Calcula dia atual, conclusão e percentual sem arredondamento enganoso."""
        plan = build_plan_catalog(self.database, "bpm")["john"]
        summary = progress_summary(plan, {"completed_days": [1, 2], "current_day": 3,
                                          "started_at": "2026-08-27T10:00:00-03:00"})
        self.assertEqual(2, summary["completed"])
        self.assertEqual(3, summary["current_day"])
        self.assertIsNotNone(summary["expected_end"])


if __name__ == "__main__":
    unittest.main()
