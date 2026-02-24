pipeline {
  agent {
    label 'ubuntu-2404'
  }

  triggers {
    parameterizedCron(env.BRANCH_NAME == 'master' ? '''H 23 * * *''' : '')
  }

  options {
    buildDiscarder(logRotator(numToKeepStr: '10'))
    disableConcurrentBuilds()
    skipDefaultCheckout false
    timeout(time: 30, unit: 'MINUTES')
  }

  environment {
    PUBLISH_LOCATION = 'updates'
    BUILD_TIMESTAMP = sh(returnStdout: true, script: 'date +%Y%m%d%H%M').trim()
    TYCHO_VERSION = '4.0.10'
    TARGET_PLATFORM_PRIMARY = 'xpect_r202403'
    TARGET_PLATFORM_LATEST = 'xpect_latest'
  }

  parameters {
    choice(
      name: 'BUILD_TYPE',
      choices: ['nightly', 'milestone', 'release'],
      description: '''
        Choose the type of build.
        Note that a release build will not promote the build, but rather will promote the most recent milestone build.
        '''
    )

    booleanParam(
      name: 'ECLIPSE_SIGN',
      defaultValue: true,
      description: '''
        Choose whether or not the bundles will be signed.
        This is relevant only for nightly and milestone builds.
      '''
    )

    booleanParam(
      name: 'PROMOTE',
      defaultValue: true,
      description: 'Whether to promote the build to the download server.'
    )
  }

  stages {
    stage('Display Parameters') {
      steps {
        script {
          env.BUILD_TYPE = params.BUILD_TYPE
          env.TARGET_PLATFORM = params.TARGET_PLATFORM

          if (env.BRANCH_NAME == 'master') {
            // Only sign the master branch.
            env.ECLIPSE_SIGN = params.ECLIPSE_SIGN
          } else {
            env.ECLIPSE_SIGN =  false
          }

            // Only promote signed builds.
          env.PROMOTE = params.PROMOTE && (env.ECLIPSE_SIGN == 'true')
        }
        echo """
BUILD_TIMESTAMP=${env.BUILD_TIMESTAMP}
BUILD_TYPE=${env.BUILD_TYPE}
ECLIPSE_SIGN=${env.ECLIPSE_SIGN}
PROMOTE=${env.PROMOTE}
BRANCH_NAME=${env.BRANCH_NAME}
"""
      }
    }

    stage('Test With Latest') {
      steps {
        wrap([$class: 'Xvnc', useXauthority: true]) {
          withMaven(jdk: 'temurin-jdk21-latest', maven: 'apache-maven-3.9.9', options: [junitPublisher(disabled: false)]) {
            dir('.') {
              sh '''
                mvn \
                  --fail-at-end \
                  --no-transfer-progress \
                  --update-snapshots \
                  -P!promote \
                  -Dmaven.repo.local=xpect-local-maven-repository \
                  -Dmaven.artifact.threads=16 \
                  -Declipsesign=false \
                  -Dtycho-version=${TYCHO_VERSION} \
                  -Dtarget-platform=${TARGET_PLATFORM_LATEST} \
                  clean \
                  integration-test
                '''
            }
          }
        }
      }
    }

    stage('Build With Primary') {
      steps {
        sshagent(['projects-storage.eclipse.org-bot-ssh']) {
          wrap([$class: 'Xvnc', useXauthority: true]) {
            withMaven(jdk: 'temurin-jdk17-latest', maven: 'apache-maven-3.9.9', options: [junitPublisher(disabled: false)]) {
              dir('.') {
                sh '''
                  if [[ $PROMOTE != true ]]; then
                    promotion_argument='-P!promote'
                  fi
                  mvn \
                    --fail-at-end \
                    --no-transfer-progress \
                    --update-snapshots \
                    $promotion_argument \
                    -Dmaven.repo.local=xpect-local-maven-repository \
                    -Dmaven.artifact.threads=16 \
                    -Declipsesign=${ECLIPSE_SIGN} \
                    -Dtycho-version=${TYCHO_VERSION} \
                    -Dtarget-platform=${TARGET_PLATFORM_PRIMARY} \
                    -Dbuild.id=${BUILD_TIMESTAMP} \
                    -Dgit.commit=$GIT_COMMIT \
                    -Dbuild.type=$BUILD_TYPE \
                    -Dorg.eclipse.justj.p2.manager.build.url=$JOB_URL \
                    -Dorg.eclipse.justj.p2.manager.relative=$PUBLISH_LOCATION \
                    clean \
                    verify
                  '''
              }
            }
          }
        }
      }
    }
  }

  post {
    failure {
      mail to: 'ed.merks@gmail.com',
      subject: "[Xpect CI] Build Failure ${currentBuild.fullDisplayName}",
      mimeType: 'text/html',
      body: "Project: ${env.JOB_NAME}<br/>Build Number: ${env.BUILD_NUMBER}<br/>Build URL: ${env.BUILD_URL}<br/>Console: ${env.BUILD_URL}/console"
      archiveArtifacts allowEmptyArchive: true, artifacts: '**'
    }

    fixed {
      mail to: 'ed.merks@gmail.com',
      subject: "[Xpect CI] Back to normal ${currentBuild.fullDisplayName}",
      mimeType: 'text/html',
      body: "Project: ${env.JOB_NAME}<br/>Build Number: ${env.BUILD_NUMBER}<br/>Build URL: ${env.BUILD_URL}<br/>Console: ${env.BUILD_URL}/console"
    }

    success {
      archiveArtifacts artifacts: 'org.eclipse.xpect.releng/p2-repository/target/repository/**/*.*,org.eclipse.xpect.releng/p2-repository/target/org.eclipse.xpect.repository-*.zip'
    }

    always {
      junit allowEmptyResults: true, testResults: '**/TEST-*.xml'
    }

    cleanup {
      deleteDir()
      sendMatrixMessage()
    }
  }
}

def sendMatrixMessage() {
  def curResult = currentBuild.currentResult
  def lastResult = 'NEW'
  if (currentBuild.previousBuild != null) {
    lastResult = currentBuild.previousBuild.result
  }

  echo "matrix result check: curResult=${curResult} lastResult=${lastResult}"

  if (curResult != 'SUCCESS' || lastResult != 'SUCCESS') {
    def color = ''
    switch (curResult) {
      case 'SUCCESS':
        color = '#00FF00'
        break
      case 'UNSTABLE':
        color = '#FFFF00'
        break
      case 'FAILURE':
        color = '#FF0000'
        break
      default: // e.g. ABORTED
        color = '#666666'
    }

    echo "matrix send message"
    matrixSendMessage https: true,
      hostname: 'matrix.eclipse.org',
      accessTokenCredentialsId: "matrix-token",
      roomId: '!aFWRHMCLJDZBzuNIRD:matrix.eclipse.org',
      body: "${lastResult} => ${curResult} ${env.BUILD_URL} | ${env.JOB_NAME}#${env.BUILD_NUMBER}",
      formattedBody: "<div><font color='${color}'>${lastResult} => ${curResult}</font> | <a href='${env.BUILD_URL}' target='_blank'>${env.JOB_NAME}#${env.BUILD_NUMBER}</a></div>"
  }
}
