@Library('socrata-pipeline-library') _

def server_redshift = 'server-redshift'
def store_redshift = 'store-redshift'

def isPr = env.CHANGE_ID != null

def dockerize_server_redshift = new com.socrata.Dockerize(steps, server_redshift, env.BUILD_NUMBER)
def dockerize_store_redshift = new com.socrata.Dockerize(steps, store_redshift, env.BUILD_NUMBER)


pipeline {
  options {
    timeout(time: 60, unit: 'MINUTES')
    buildDiscarder(logRotator(numToKeepStr: '20'))
    ansiColor('xterm')
  }
  parameters {
    string(name: 'AGENT', defaultValue: 'worker-java17', description: 'Which build agent to use?')
    string(name: 'BRANCH_SPECIFIER', defaultValue: 'origin/main', description: 'Use this branch for building the artifact.')
  }
  agent {
    label params.AGENT
  }
  tools {
    maven 'Maven 3.8.8'
  }
  triggers {
    issueCommentTrigger('^retest$')
  }
  environment {
    SERVICE = 'soql-redshift-adapter'
    WEBHOOK_ID = 'WEBHOOK_SIQ_PERFORMANCE'
  }
  stages {
    stage('Pull Request:') {
      when { changeRequest() }
      stages {
        stage('Testing') {
          steps {
            githubNotify context: 'unit', description: 'Running "mvn test"', status: 'PENDING'
            withCredentials([usernamePassword(credentialsId: 'shared-eng-artifactory-creds', usernameVariable: 'ARTIFACTORY_USERNAME', passwordVariable: 'ARTIFACTORY_PASSWORD')]) {
              sh 'mvn test -Dartifactory.username=${ARTIFACTORY_USERNAME} -Dartifactory.password=${ARTIFACTORY_PASSWORD} -s settings.xml'
            }
          }
          post {
            always {
              junit "**/target/surefire-reports/*.xml"
            }
            success {
              githubNotify context: 'unit', description: 'Tests passed', status: 'SUCCESS'
            }
            failure {
              githubNotify context: 'unit', description: 'Tests failed', status: 'FAILURE', targetUrl: "${env.BUILD_URL}/console"
            }
          }
        }
      }
    }
    stage('Build:') {
      when {
        not { expression { isPr } }
      }
      stages {
        stage('Build and Push Docker Image') {
          steps {
            withCredentials([usernamePassword(credentialsId: 'shared-eng-artifactory-creds', usernameVariable: 'ARTIFACTORY_USERNAME', passwordVariable: 'ARTIFACTORY_PASSWORD')]) {
              sh 'mvn install -DskipTests=false -Dartifactory.username=${ARTIFACTORY_USERNAME} -Dartifactory.password=${ARTIFACTORY_PASSWORD} -s settings.xml'
            }

            script {
              dockerize_server_redshift.setPushToLatest(false)
              dockerize_server_redshift.specify_tag_and_push("${server_redshift}:latest", env.VERSION, "all")
              dockerize_store_redshift.setPushToLatest(false)
              dockerize_store_redshift.specify_tag_and_push("${store_redshift}:latest", env.VERSION, "all")
            }
          }
          post {
            failure {
              teamsMessage(message: "[${env.service}](${env.BUILD_URL}): Building and pushing docker image has failed", webhookCredentialID: WEBHOOK_ID)
            }
          }
        }
      }
    }
  }
}