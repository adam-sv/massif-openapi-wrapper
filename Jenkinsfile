// Tell Jenkins how to build projects from this repository
pipeline {
    parameters {

        // DISTRIBUTION
        booleanParam(defaultValue: true, description: 'Set to true if you want to deploy to snapshot repository', name: 'DEPLOY_SNAPSHOT')
        booleanParam(defaultValue: false, description: 'Set to true if you want to create a release', name: 'RELEASE')
        choice(choices: 'MILESTONE\nRC\nRELEASE', description: 'Type of distribution build (only applies if release is enabled).', name: 'DISTRIBUTION_TYPE')
        string(defaultValue: '0', description: 'Milestone/RC number/suffix.', name: 'DISTRIBUTION_SUFFIX')
    }

    agent {
        label 'matlab'
    }

    options {
        // Keep only the last 15 builds
        buildDiscarder(logRotator(numToKeepStr: '15'))
        // Do not execute the same pipeline concurrently
        disableConcurrentBuilds()
    }

    tools {
        jdk 'Oracle JDK 8'
    }

    stages {

        stage('Build Massif Wrapper') {
            steps {
                withCredentials([usernamePassword(credentialsId: 'nexus-buildserver-deploy', passwordVariable: 'NEXUS_PASSWORD', usernameVariable: 'NEXUS_USER')]) {
                    sh '''
                        ./gradlew clean build
                    '''
                }
            }
        }

        stage('Deploy to Local Repo') {
            when {
                branch "master"
                expression { params.DEPLOY_SNAPSHOT == false}
            }
            steps {
                withCredentials([usernamePassword(credentialsId: 'nexus-buildserver-deploy', passwordVariable: 'NEXUS_PASSWORD', usernameVariable: 'NEXUS_USER')]) {
                    sh "./gradlew publish"
                }
            }
        }

        stage('Deploy to Nexus') {
            when {
                branch "master"
                expression { params.DEPLOY_SNAPSHOT == true }
            }
            steps {
                withCredentials([usernamePassword(credentialsId: 'nexus-buildserver-deploy', passwordVariable: 'NEXUS_PASSWORD', usernameVariable: 'NEXUS_USER')]) {
                    sh "./gradlew publish -PdeployUrl='https://build.incquerylabs.com/nexus/repository/massif-wrapper-snapshots/'"
                }
            }
        }

        stage('Release') {
            when {
                branch "master"
                expression { params.RELEASE == true }
            }
            steps {
                withCredentials([usernamePassword(credentialsId: 'nexus-buildserver-deploy', passwordVariable: 'NEXUS_PASSWORD', usernameVariable: 'NEXUS_USER')]) {
                    sh "./gradlew publish -PdeployUrl='https://build.incquerylabs.com/nexus/repository/massif-wrapper-releases/'"
                }
            }
        }
    }

    post {
        always {
            archiveArtifacts 'rest-server/build/libs/massif-wrapper-rest-server-runnable.jar'
            archiveArtifacts 'massif_scripts/massif_functions.m'
        }
    }
}
