
def main() {
    def BRANCHES = "${BRANCHLIST}".split(',')
    def PROFILES = "${PROFILELIST}".split(',')
    def USE_SECURITY = '-'
    def runbranchstage = [:]

    for (x in BRANCHES) {
        
        if ("${SECURITY_SERVICE_NEEDED}" == 'true') {
            USE_SECURITY = '-security-'
        } 
        
        def BRANCH = x
        runbranchstage["Test ${ARCH}${USE_DB}${USE_SECURITY}${BRANCH}"]= {
            node("${SLAVE}") {
                stage ('Checkout edgex-taf repository') {
                    checkout([$class: 'GitSCM',
                        branches: [[name: "*/${BRANCH}"]],
                        doGenerateSubmoduleConfigurations: false, 
                        extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: '']], 
                        submoduleCfg: [], 
                        userRemoteConfigs: [[url: 'https://github.com/edgexfoundry/edgex-taf.git']]
                        ])
                }

                stage ("Deploy EdgeX - ${ARCH}${USE_DB}${USE_SECURITY}${BRANCH}") {
                    dir ('TAF/utils/scripts/docker') {
                        sh "sh get-compose-file.sh ${USE_DB} ${ARCH} ${USE_SECURITY}"
                        sh 'ls *.yml *.yaml'
                    }

                    sh "docker run --rm --network host -v ${env.WORKSPACE}:${env.WORKSPACE}:rw,z -w ${env.WORKSPACE} \
                            -e COMPOSE_IMAGE=${COMPOSE_IMAGE} -e SECURITY_SERVICE_NEEDED=${SECURITY_SERVICE_NEEDED} \
                            -v /var/run/docker.sock:/var/run/docker.sock ${TAF_COMMOM_IMAGE} \
                            --exclude Skipped -u functionalTest/deploy-edgex.robot -p default"
                }

                echo "Profiles : ${PROFILES}"
                stage ("Run Tests Script - ${ARCH}${USE_DB}${USE_SECURITY}${BRANCH}") {
                    script {
                        for (y in PROFILES) {
                            def profile = y
                            echo "Profile : ${profile}"
                            echo "===== Deploy ${profile} ====="
                            sh "docker run --rm --network host -v ${env.WORKSPACE}:${env.WORKSPACE}:rw,z -w ${env.WORKSPACE} \
                                    -e COMPOSE_IMAGE=${COMPOSE_IMAGE} -e ARCH=${ARCH} \
                                    -v /var/run/docker.sock:/var/run/docker.sock ${TAF_COMMOM_IMAGE} \
                                    --exclude Skipped -u functionalTest/device-service/deploy_device_service.robot -p ${profile}"

                            echo "===== Run ${profile} Test Case ====="
                            sh "docker run --rm --network host -v ${env.WORKSPACE}:${env.WORKSPACE}:rw,z -w ${env.WORKSPACE} \
                                    -e COMPOSE_IMAGE=${COMPOSE_IMAGE} -e ARCH=${ARCH} \
                                    -v /var/run/docker.sock:/var/run/docker.sock ${TAF_COMMOM_IMAGE} \
                                    --exclude Skipped -u functionalTest/device-service/common -p ${profile}"
                            
                            dir ('TAF/testArtifacts/reports/rename-report') {
                                sh "cp ../edgex/log.html ${profile}-common-log.html"
                                sh "cp ../edgex/report.xml ${profile}-common-report.xml"
                            }

                            echo "===== Shutdown ${profile} ====="
                            sh "docker run --rm --network host -v ${env.WORKSPACE}:${env.WORKSPACE}:rw,z -w ${env.WORKSPACE} \
                                    -e COMPOSE_IMAGE=${COMPOSE_IMAGE} -e ARCH=${ARCH} \
                                    -v /var/run/docker.sock:/var/run/docker.sock ${TAF_COMMOM_IMAGE} \
                                    --exclude Skipped -u functionalTest/device-service/shutdown_device_service.robot -p ${profile}"
                        }
                    }
                }
                stage ("Stash Report - ${ARCH}${USE_DB}${USE_SECURITY}${BRANCH}") {
                    echo '===== Merge Reports ====='
                    sh "docker run --rm --network host -v ${env.WORKSPACE}:${env.WORKSPACE}:rw,z -w ${env.WORKSPACE} \
                                -e COMPOSE_IMAGE=${COMPOSE_IMAGE} -v /var/run/docker.sock:/var/run/docker.sock ${TAF_COMMOM_IMAGE} \
                                rebot --inputdir TAF/testArtifacts/reports/rename-report \
                                --outputdir TAF/testArtifacts/reports/${BRANCH}-report"

                    dir ("TAF/testArtifacts/reports/${BRANCH}-report") {
                        // Check if the merged-report folder exists
                        def mergeExist = sh (
                            script: 'ls ../ | grep merged-report',
                            returnStatus: true
                        )
                        if (mergeExist != 0) {
                            sh 'mkdir ../merged-report'
                        }
                        //Copy log file to merged-report folder
                        sh "cp log.html ../merged-report/${ARCH}${USE_DB}${USE_SECURITY}${BRANCH}-log.html"
                        sh "cp result.xml ../merged-report/${ARCH}${USE_DB}${USE_SECURITY}${BRANCH}-report.xml"
                    }
                    stash name: "${ARCH}${USE_DB}${USE_SECURITY}${BRANCH}-report", includes: "TAF/testArtifacts/reports/merged-report/*", allowEmpty: true
                }
                stage ("Shutdown EdgeX - ${ARCH}${USE_DB}${USE_SECURITY}${BRANCH}") {
                    sh "docker run --rm --network host -v ${env.WORKSPACE}:${env.WORKSPACE}:rw,z -w ${env.WORKSPACE} \
                            -e COMPOSE_IMAGE=${COMPOSE_IMAGE} -v /var/run/docker.sock:/var/run/docker.sock ${TAF_COMMOM_IMAGE} \
                            --exclude Skipped -u functionalTest/shutdown.robot -p default"
                }
            }
        }
    }
    parallel runbranchstage
}

return this
