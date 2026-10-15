# Overview of how CI is set up

## Releases
* are manual
* done by running `promote-image-to-prod.yml` workflow on a commit
  * or by pushing a version tag manually
  * either way it ends up tagged.

## Acceptance environment
* when PR is merged, the main branch is automatically deployed to acceptance
* you can also manually pick a to branch to deploy to acceptance (`build-and-deploy-acc.yml` workflow)
  * useful when testing a PR

## Files
ci-tests.yml
* Runs tests

build-and-deploy-acc.yml
* Builds the docker image
* invokes deploy to ACC

deploy-image.yml
* Contains actual deployment logic to VPS
* Meaning it just receives image and environment as params.

promote-image-to-prod.yml
* Looks up the ACC image for selected commit
* invokes deploy to PROD

rollback.yml
* Looks up the image for the release tag you specify
* invokes deploy to PROD
