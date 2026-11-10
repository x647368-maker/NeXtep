#!/usr/bin/env python3
"""Require every source-level signature pin to agree with the freshly issued key.
This does not include or fetch private signing material.
"""
from pathlib import Path
import re, unittest, hashlib, json
ROOT=Path(__file__).resolve().parents[1]
OLD='b25ba1046d53510a1378b3404160d27a222a3ec9993b0a722b8f61304ac4abdd'
NEW='3703b59c48b1c30df872fbcd8fa01905b9a6c684f151ddbb0d05e5b5726468fe'
PATHS=(
  'app/src/main/kotlin/io/github/lujinxin/nextep/config/UpdatePolicy.kt',
  'scripts/prepare-update.py',
  'scripts/publish-update-feed.py',
  '.github/workflows/atomic-android.yml',
)
class SigningRotationContract(unittest.TestCase):
 def test_all_new_certificate_pins(self):
  for p in PATHS:
   s=(ROOT/p).read_text()
   self.assertIn(NEW,s,p)
   self.assertNotIn(OLD,s,p)
   self.assertEqual(1,s.count(NEW),p)
 def test_source_review_is_exact_and_reversible(self):
  f=PATHS[0]
  s=(ROOT/f).read_text()
  review=json.loads((ROOT/'scripts/reviewed-rekey-0.9.18.json').read_text())['files'][f]
  self.assertEqual(hashlib.sha256(s.encode()).hexdigest(), review['updated_sha256'])
  for patch in review['patches']:
   self.assertEqual(s.count(patch['after']),1)
   s=s.replace(patch['after'],patch['before'],1)
  self.assertEqual(hashlib.sha256(s.encode()).hexdigest(),review['baseline_sha256'])
 def test_old_signature_not_accepted_by_new_release_metadata(self):
  policy=(ROOT/PATHS[0]).read_text()
  self.assertIn('require(archiveSigners == installedSigners && archiveSigners == setOf(CERT))',policy)
  self.assertIn('require(versionCode > 0 && size in 1..MAX_APK && applicationId == APP && certificate == CERT)',policy)
 def test_private_key_is_never_in_repository(self):
  self.assertFalse(any(ROOT.rglob('*.p12')))
  self.assertFalse(any(ROOT.rglob('*.pfx')))
  self.assertFalse(any(ROOT.rglob('*.keystore')))
if __name__=='__main__':unittest.main()
