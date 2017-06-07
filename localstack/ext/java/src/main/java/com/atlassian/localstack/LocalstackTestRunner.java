package com.atlassian.localstack;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import org.apache.commons.io.FileUtils;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.BlockJUnit4ClassRunner;
import org.junit.runners.model.InitializationError;

import com.amazonaws.util.IOUtils;

/**
 * Simple JUnit test runner that automatically downloads, installs, starts,
 * and stops the LocalStack local cloud infrastructure components.
 *
 * Should work cross-OS, however has been only tested under Unix (Linux/MacOS).
 *
 * @author Waldemar Hummer
 */
public class LocalstackTestRunner extends BlockJUnit4ClassRunner {

	private static final AtomicReference<Process> INFRA_STARTED = new AtomicReference<Process>();
	private static String CONFIG_FILE_CONTENT = "";

	private static final String INFRA_READY_MARKER = "Ready.";
	private static final String TMP_INSTALL_DIR = System.getProperty("java.io.tmpdir") +
			File.separator + "localstack_install_dir";
	private static final String ADDITIONAL_PATH = "/usr/local/bin/";
	private static final String LOCALHOST = "localhost";
	private static final String LOCALSTACK_REPO_URL = "https://github.com/atlassian/localstack";

	private static final Logger LOG = Logger.getLogger(LocalstackTestRunner.class.getName());

	public LocalstackTestRunner(Class<?> klass) throws InitializationError {
		super(klass);
	}

	/* SERVICE ENDPOINTS */

	public static String getEndpointS3() {
		String s3Endpoint = ensureInstallationAndGetEndpoint("s3");
		/*
		 * Use the domain name wildcard *.localhost.atlassian.io which maps to 127.0.0.1
		 * We need to do this because S3 SDKs attempt to access a domain <bucket-name>.<service-host-name>
		 * which by default would result in <bucket-name>.localhost, but that name cannot be resolved
		 * (unless hardcoded in /etc/hosts)
		 */
		s3Endpoint = s3Endpoint.replace("localhost", "test.localhost.atlassian.io");
		return s3Endpoint;
	}

	public static String getEndpointKinesis() {
		return ensureInstallationAndGetEndpoint("kinesis");
	}

	public static String getEndpointLambda() {
		return ensureInstallationAndGetEndpoint("lambda");
	}

	public static String getEndpointDynamoDB() {
		return ensureInstallationAndGetEndpoint("dynamodb");
	}

	public static String getEndpointDynamoDBStreams() {
		return ensureInstallationAndGetEndpoint("dynamodbstreams");
	}

	public static String getEndpointAPIGateway() {
		return ensureInstallationAndGetEndpoint("apigateway");
	}

	public static String getEndpointElasticsearch() {
		return ensureInstallationAndGetEndpoint("elasticsearch");
	}

	public static String getEndpointElasticsearchService() {
		return ensureInstallationAndGetEndpoint("es");
	}

	public static String getEndpointFirehose() {
		return ensureInstallationAndGetEndpoint("firehose");
	}

	public static String getEndpointSNS() {
		return ensureInstallationAndGetEndpoint("sns");
	}

	public static String getEndpointSQS() {
		return ensureInstallationAndGetEndpoint("sqs");
	}

	public static String getEndpointRedshift() {
		return ensureInstallationAndGetEndpoint("redshift");
	}

	public static String getEndpointSES() {
		return ensureInstallationAndGetEndpoint("ses");
	}

	public static String getEndpointRoute53() {
		return ensureInstallationAndGetEndpoint("route53");
	}

	public static String getEndpointCloudFormation() {
		return ensureInstallationAndGetEndpoint("cloudformation");
	}

	public static String getEndpointCloudWatch() {
		return ensureInstallationAndGetEndpoint("cloudwatch");
	}

	/* UTILITY METHODS */

	@Override
	public void run(RunNotifier notifier) {
		setupInfrastructure();
		super.run(notifier);
	}

	private static void ensureInstallation() {
		File dir = new File(TMP_INSTALL_DIR);
		File constantsFile = new File(dir, "localstack/constants.py");
		if(!constantsFile.exists()) {
			LOG.info("Installing LocalStack to temporary directory (this may take a while): " + TMP_INSTALL_DIR);
			try {
				FileUtils.deleteDirectory(dir);
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
			exec("git clone " + LOCALSTACK_REPO_URL + " " + TMP_INSTALL_DIR);
			exec("cd \"" + TMP_INSTALL_DIR + "\"; make install");
		}
	}

	private static void killProcess(Process p) {
		p.destroy();
		p.destroyForcibly();
	}

	private static String ensureInstallationAndGetEndpoint(String service) {
		ensureInstallation();
		return getEndpoint(service);
	}

	private static String getEndpoint(String service) {
		String regex = ".*DEFAULT_PORT_" + service.toUpperCase() + "\\s*=\\s*([0-9]+).*";
		String port = Pattern.compile(regex, Pattern.DOTALL | Pattern.MULTILINE).matcher(CONFIG_FILE_CONTENT).replaceAll("$1");
		return "http://" + LOCALHOST + ":" + port + "/";
	}

	private static Process exec(String ... cmd) {
		return exec(true, cmd);
	}

	private static Process exec(boolean wait, String ... cmd) {
		try {
			if (cmd.length == 1 && !new File(cmd[0]).exists()) {
				cmd = new String[]{"bash", "-c", cmd[0]};
			}
			Map<String, String> env = new HashMap<>(System.getenv());
			ProcessBuilder builder = new ProcessBuilder(cmd);
			builder.environment().put("PATH", ADDITIONAL_PATH + ":" + env.get("PATH"));
			final Process p = builder.start();
			if (wait) {
				int code = p.waitFor();
				if(code != 0) {
					String stderr = IOUtils.toString(p.getErrorStream());
					String stdout = IOUtils.toString(p.getInputStream());
					throw new IllegalStateException("Failed to run command '" + cmd + "', return code " + code +
							".\nSTDOUT: " + stdout + "\nSTDERR: " + stderr);
				}
			} else {
				/* make sure we destroy the process on JVM shutdown */
				Runtime.getRuntime().addShutdownHook(new Thread() {
					public void run() {
						killProcess(p);
					}
				});
			}
			return p;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private void setupInfrastructure() {
		synchronized (INFRA_STARTED) {
			ensureInstallation();
			if(INFRA_STARTED.get() != null) return;
			String[] cmd = new String[]{"make", "-C", TMP_INSTALL_DIR, "infra"};
			Process proc;
			try {
				proc = exec(false, cmd);
				BufferedReader r1 = new BufferedReader(new InputStreamReader(proc.getInputStream()));
				String line;
				LOG.info("Waiting for infrastructure to be spun up");
				while((line = r1.readLine()) != null) {
					if(INFRA_READY_MARKER.equals(line)) {
						break;
					}
				}
				/* read contents of LocalStack config file */
				String configFile = TMP_INSTALL_DIR + File.separator + "localstack" +  File.separator + "constants.py";
				CONFIG_FILE_CONTENT = IOUtils.toString(new FileInputStream(configFile));
				INFRA_STARTED.set(proc);
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
	}

	public static void teardownInfrastructure() {
		Process proc = INFRA_STARTED.get();
		if(proc == null) {
			return;
		}
		killProcess(proc);
	}
}
