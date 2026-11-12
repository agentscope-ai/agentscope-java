import unittest
from unittest.mock import Mock
from durable_sampler import sample


class DurableSamplerTests(unittest.TestCase):
    def test_sampler_counts_only_nonterminal_turns_and_keeps_original_statuses(self):
        connection=Mock()
        connection.execute.return_value.fetchall.return_value=[
            {'status':'running','count':3},{'status':'waiting_tool','count':97},
            {'status':'succeeded','count':12},{'status':'failed','count':1}]
        value=sample(connection,'batch-without-sql-interpolation',1234)
        self.assertEqual(value['active_turns'],100)
        self.assertEqual(value['statuses']['succeeded'],12)
        self.assertEqual(value['source'],'independent_sql')
        self.assertLessEqual(value['query_started_at'],value['sampled_at'])
        self.assertEqual(connection.execute.call_args.args[1],(1234,'batch-without-sql-interpolation'))

    def test_database_failure_cannot_be_reported_as_zero_concurrency(self):
        connection=Mock()
        connection.execute.side_effect=RuntimeError('unavailable')
        with self.assertRaises(RuntimeError):sample(connection,'batch',1234)
        with self.assertRaises(ValueError):sample(connection,'',1234)
