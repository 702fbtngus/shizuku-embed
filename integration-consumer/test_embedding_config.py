"""Exercise the real identity script without loading Android plugins or SDKs."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


REPO = Path(__file__).resolve().parents[1]
WRAPPER = REPO / ('gradlew.bat' if os.name == 'nt' else 'gradlew')


class EmbeddingConfigurationTest(unittest.TestCase):
    def run_configuration(self, **properties):
        build_dir = REPO / 'build'
        build_dir.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='identity-check-', dir=build_dir) as directory:
            project = Path(directory)
            (project / 'settings.gradle').write_text("rootProject.name = 'identity-check'\n")
            (project / 'build.gradle').write_text("""
apply from: providers.gradleProperty('embeddingScript').get()
tasks.register('reportIdentity') {
    doLast {
        println 'EMBEDDING_CONFIG=' + groovy.json.JsonOutput.toJson([
            applicationId: managerApplicationId,
            permissionPrefix: managerPermissionPrefix,
            processName: serverProcessName,
            configName: serverConfigName,
            embedded: embeddedManager
        ])
    }
}
""")
            command = [str(WRAPPER), '--no-daemon', '--console=plain', '-q',
                       '-p', str(project), '-PshizukuEmbedded=true',
                       '-PembeddingScript=' + str(REPO / 'embedding.gradle')]
            command.extend('-P' + key + '=' + value for key, value in properties.items())
            command.append('reportIdentity')
            environment = os.environ.copy()
            for key in list(environment):
                if key.startswith('ORG_GRADLE_PROJECT_shizuku'):
                    environment.pop(key)
            return subprocess.run(command, cwd=REPO, env=environment,
                                  capture_output=True, text=True, timeout=120)

    def identity(self, **properties):
        result = self.run_configuration(**properties)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        line = next(line for line in result.stdout.splitlines()
                    if line.startswith('EMBEDDING_CONFIG='))
        return json.loads(line.split('=', 1)[1])

    def test_defaults_follow_each_host(self):
        for host in ['com.example.shizuku.host', 'org.example.embedded.demo']:
            with self.subTest(host=host):
                self.assertEqual(self.identity(shizukuHostApplicationId=host), {
                    'applicationId': host,
                    'permissionPrefix': host + '.shizuku.permission',
                    'processName': host + '_shizuku_server',
                    'configName': host + '-shizuku.json',
                    'embedded': True,
                })

    def test_existing_backend_identity_can_be_retained(self):
        config = self.identity(shizukuHostApplicationId='com.example.host',
                               shizukuServerProcessName='existing_app_server',
                               shizukuServerConfigName='existing-app.json')
        self.assertEqual(config['processName'], 'existing_app_server')
        self.assertEqual(config['configName'], 'existing-app.json')

    def test_missing_or_unsafe_identities_fail(self):
        cases = [({}, 'shizukuHostApplicationId'),
                 ({'shizukuHostApplicationId': 'moe.shizuku.privileged.api'},
                  'shizukuHostApplicationId'),
                 ({'shizukuHostApplicationId': 'com.example.host',
                   'shizukuServerProcessName': 'shizuku_server'}, 'shizukuServerProcessName'),
                 ({'shizukuHostApplicationId': 'com.example.host',
                   'shizukuServerConfigName': '../shared.json'}, 'shizukuServerConfigName')]
        for properties, message in cases:
            with self.subTest(properties=properties):
                result = self.run_configuration(**properties)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(message, result.stdout + result.stderr)


if __name__ == '__main__':
    unittest.main()
