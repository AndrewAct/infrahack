"""Unit checks for S3 adaptation; no credentials, S3 calls, or data experiment."""

import unittest
from unittest.mock import Mock

from botocore.exceptions import ClientError

from small_file_experiment import object_exists, object_summary, s3_parts


class StorageHelpersTest(unittest.TestCase):
    def test_only_accepts_s3_uris(self):
        self.assertEqual(s3_parts("s3://lab/runs/a/"), ("lab", "runs/a/"))
        for uri in (
            "file:///tmp/warehouse",
            "https://example.com",
            "s3:///missing",
            "s3://lab/p?secret=x",
        ):
            with self.subTest(uri=uri), self.assertRaises(ValueError):
                s3_parts(uri)

    def test_summary_reads_all_pages_including_empty_page(self):
        client = Mock()
        client.get_paginator.return_value.paginate.return_value = [
            {"Contents": [{"Size": 10}, {"Size": 20}]},
            {},
            {"Contents": [{"Size": 30}]},
        ]
        self.assertEqual(
            object_summary(client, "s3://lab/runs/a/"), {"objects": 3, "bytes": 60}
        )
        client.get_paginator.return_value.paginate.assert_called_once_with(
            Bucket="lab", Prefix="runs/a/"
        )

    def test_missing_is_false_but_permission_error_is_not_hidden(self):
        client = Mock()
        client.head_object.side_effect = ClientError(
            {"Error": {"Code": "404"}}, "HeadObject"
        )
        self.assertFalse(object_exists(client, "s3://lab/key"))
        client.head_object.side_effect = ClientError(
            {"Error": {"Code": "403"}}, "HeadObject"
        )
        with self.assertRaises(ClientError):
            object_exists(client, "s3://lab/key")


if __name__ == "__main__":
    unittest.main()
