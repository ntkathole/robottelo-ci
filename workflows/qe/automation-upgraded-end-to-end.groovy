@Library("github.com/SatelliteQE/robottelo-ci") _

pipeline {
    agent { label "sat6-${satellite_version}" }
    environment {
        FROM_VERSION = " "
        DISTRO = "${os}"
    }
    stages {
        stage('Clean Workspace') {
            steps {
                deleteDir()
            }
        }
        stage('Source Environment') {
           steps {
               git defaults.robottelo
               make_venv python: defaults.python
           }
        }
        stage("Configure robottelo") {
            steps {
                script {
                configFileProvider(
                [configFile(fileId: 'bc5f0cbc-616f-46de-bdfe-2e024e84fcbf', variable: 'CONFIG_FILES')]) {
                // Start to populate robottelo.properties file
                sh_venv '''
                set -o nounset
                source ${CONFIG_FILES}
                cp config/robottelo.properties ./robottelo.properties
                cp config/robottelo.yaml ./robottelo.yaml
                export PYCURL_SSL_LIBRARY=\$(curl -V | sed -n 's/.*\\(NSS\\|OpenSSL\\).*/\\L\\1/p')
                pip install -U -r requirements.txt docker-py pytest-xdist==1.25.0 sauceclient
                pip install -r requirements-optional.txt
                '''
                load('config/compute_resources.groovy')
                load('config/sat6_upgrade.groovy')
                // Sauce Labs Configuration and pytest-env setting.
                if ("${SATELLITE_VERSION}" != "6.3" ){
                    SAUCE_BROWSER="chrome"
                    sh_venv '''
                    pip install -U pytest-env
                    env =
                        PYTHONHASHSEED=0
                    '''
                }
                withCredentials([string(credentialsId: 'SAUCELABS_KEY', variable: 'SAUCELABS_KEY'), string(credentialsId: 'BUGZILLA_PASSWORD', variable: 'BUGZILLA_PASSWORD')]) {
                sauce_args = [:]
                image_args = [:]
                network_args = [:]
                dist_args = [:]
                BROWSER_VERSION = ''
                SELENIUM_VERSION = ''
                if ( "${SAUCE_PLATFORM}" != "no_saucelabs" ) {
                    echo "The Sauce Tunnel Identifier for Server Hostname ${SERVER_HOSTNAME} is ${TUNNEL_IDENTIFIER}"
                    if ( "${SAUCE_BROWSER}" == "edge" ){
                        BROWSER_VERSION='14.14393'
                        }
                    else if ( "${SAUCE_BROWSER}" == "chrome" ) {
                        BROWSER_VERSION='63.0'
                    }
                    if ( "${SATELLITE_VERSION}" == "6.3" ) {
                        SELENIUM_VERSION='2.53.1'
                        }
                    else if ( "${SATELLITE_VERSION}" == "6.4" ) {
                        SELENIUM_VERSION='3.14.0'
                        }
                    else {
                        SELENIUM_VERSION='3.141.0'
                         }
                    sauce_args = ['browser': 'saucelabs',
                             'saucelabs_user': env.SAUCELABS_USER,
                             'saucelabs_key': SAUCELABS_KEY,
                             'webdriver':  "${SAUCE_BROWSER}",
                             'webdriver_desired_capabilities' : "platform=${SAUCE_PLATFORM},version=${BROWSER_VERSION},maxDuration=5400,idleTimeout=1000,seleniumVersion=${SELENIUM_VERSION},build=${env.BUILD_LABEL},screenResolution=1600x1200,tunnelIdentifier=${TUNNEL_IDENTIFIER},extendedDebugging=true,tags=[${env.JOB_NAME}]"
                              ]
                }
                else {
                //use zalenium
                sauce_args = [
                         'webdriver': 'chrome',
                         'webdriver_desired_capabilities' : "platform=ANY,maxDuration=5400,idleTimeout=1000,start-maximised=true,screenResolution=1600x1200,tags=[${env.JOB_NAME}]"
                          ]
                }

                // If Image Parameter is checked
                if (IMAGE != null){
                    image_agrs = ['[distro]': '',
                            'image_el6': IMAGE,
                            'image_el7': IMAGE,
                            'image_el8': IMAGE,
                            ]

                }
                // upstream = 1 for Distributions: UPSTREAM (default in robottelo.properties)
                // upstream = 0 for Distributions: DOWNSTREAM, CDN, BETA, ISO
                if ( !SATELLITE_VERSION.contains('upstream-nightly')) {
                // To set the discovery ISO name in properties file
                network_args = ['upstream':'false',
                            '[vlan_networking]':'',
                            'subnet':SUBNET,
                            'netmask': NETMASK,
                            'gateway': GATEWAY,
                            'bridge': BRIDGE,
                            '[discovery]':'',
                            'discovery_iso': DISCOVERY_ISO
                            ]
                }
                if ( !SATELLITE_DISTRIBUTION.contains('GA')) {
                    dist_args = ['cdn': 'false',
                            'sattools_repo': "rhel8=${RHEL8_TOOLS_REPO},rhel7=${RHEL7_TOOLS_REPO},rhel6=${RHEL6_TOOLS_REPO}",
                            'capsule_repo': CAPSULE_REPO
                            ]
                }
                // Bugzilla Login Details
                //AWS Access Keys Configuration
                //Robottelo Capsule Configuration
                all_args = [
                    'hostname': SERVER_HOSTNAME,
                    'screenshots_path': "${WORKSPACE}//screenshots",
                    'external_url': "http://${SERVER_HOSTNAME}:2375",
                    'bz_password': BUGZILLA_PASSWORD,
                    'bz_username': env.BUGZILLA_USER,
                    'access_key': env.AWS_ACCESSKEY_ID,
                    'secret_key': env.AWS_ACCESSKEY_SECRET,
                    '[capsule]':'',
                    'instance_name': SERVER_HOSTNAME.split('\\.')[0] + '-capsule',
                    'domain': DDNS_DOMAIN,
                    'hash': CAPSULE_DDNS_HASH,
                    'ddns_package_url': DDNS_PACKAGE_URL
                    ] + sauce_args + image_agrs + network_args + dist_args
                parse_ini ini_file: "${WORKSPACE}//robottelo.properties" , properties: all_args
                }
                }
                }
            }
        }
        stage('Run Tests') {
            steps {
                script {
                    if ("${ENDPOINT}" != "end-to-end") {
                    sh_venv '''
                    TEST_TYPE="$(echo tests/foreman/{api,cli,ui,longrun,sys,installer})"
                    set +e
                    # Run all tiers sequential tests with upgrade mark
                    $(which py.test) -v --junit-xml="${ENDPOINT}-upgrade-sequential-results.xml" \
                        -o junit_suite_name="${ENDPOINT}-upgrade-sequential" \
                        -m "upgrade and run_in_one_thread and not stubbed" \
                        ${TEST_TYPE}

                    # Run all tiers parallel tests with upgrade mark

                    $(which py.test) -v --junit-xml="${ENDPOINT}-upgrade-parallel-results.xml" -n "${ROBOTTELO_WORKERS}" \
                        -o junit_suite_name="${ENDPOINT}-upgrade-parallel" \
                        -m "upgrade and not run_in_one_thread and not stubbed" \
                        ${TEST_TYPE}
                    set -e
                    '''
                    } else if ("${ENDPOINT}" == "end-to-end") {
                    sh_venv '''
                    set +e
                    # Run end-to-end , also known as smoke tests
                    if [[ "${SATELLITE_VERSION}" != "6.3" ]]; then
                        $(which py.test) -v --junit-xml="smoke-tests-results.xml" -o junit_suite_name="smoke-tests" tests/foreman/endtoend/test_{api,cli}_endtoend.py
                    else
                        $(which py.test) -v --junit-xml="smoke-tests-results.xml" -o junit_suite_name="smoke-tests" tests/foreman/endtoend
                    fi
                    set -e
                    '''
                    } else {
                    sh_venv '''
                    make test-foreman-${ENDPOINT} PYTEST_XDIST_NUMPROCESSES=${ROBOTTELO_WORKERS}
                    '''
                    }

                    if ("${ROBOTTELO_WORKERS}" > 0) {
                      sh_venv '''
                          set +e
                          make logs-join || true
                          make logs-clean || true
                          set -e
                      '''
                    }
                    print "========================================"
                    print "Server information"
                    print "========================================"
                    print "Hostname: ${SERVER_HOSTNAME}"
                    print "Credentials: admin/changeme"
                    print "========================================"
                }
            }
        }
        stage('Trigger Upgrade Reporting Job') {
            steps {
                build job: "report-upgrade-tier-automation-results-${satellite_version}-${os}"
                parameters: [
                string(name: 'BUILD_LABEL', value: "${params.BUILD_LABEL}"),
                ]
            }
        }
        stage('Trigger polarion upgraded test run Job') {
            steps {
                sh "POLARION_RELEASE=${params.BUILD_LABEL%%-*}"
                build job: "polarion-upgraded-test-run-${satellite_version}-${os}",
                parameters: [
                string(name: 'TEST_RUN_ID', value: "${params.BUILD_LABEL} ${os} upgrade"),
                string(name: 'POLARION_RELEASE', value: "${POLARION_RELEASE} upgrade"),
                ]
            }
        }
    }
    post {
        always {
            archiveArtifacts(artifacts: "robottelo*.log","*-results.xml","robottelo.properties",
            allowEmptyArchive: true)
            junit ('*-results.xml', allowEmptyArchive: true)
        }
        success {
            send_report_email "success"
        }
        fixed {
            send_report_email "fixed"
        }
    }
}
