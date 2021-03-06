package com.imaginea.activegrid.core.models

/**
  * Created by nagulmeeras on 25/10/16.
  */

import com.amazonaws.auth.AWSCredentials
import com.amazonaws.regions.{Region, RegionUtils}
import com.amazonaws.services.autoscaling.AmazonAutoScalingClient
import com.amazonaws.services.autoscaling.model.AutoScalingGroup
import com.amazonaws.services.ec2.model._
import com.amazonaws.services.ec2.{AmazonEC2, AmazonEC2Client, model}
import com.amazonaws.services.elasticloadbalancing.AmazonElasticLoadBalancingClient
import com.amazonaws.services.elasticloadbalancing.model.LoadBalancerDescription
import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory

import scala.collection.JavaConversions._
import scala.collection.immutable.List

object AWSComputeAPI {
  val logger = Logger(LoggerFactory.getLogger(getClass.getName))

  def getInstances(amazonEC2: AmazonEC2, accountInfo: AccountInfo): List[Instance] = {

    val awsInstancesResult = getAWSInstances(amazonEC2)
    val totalsecurityGroups = amazonEC2.describeSecurityGroups.getSecurityGroups
      .foldLeft(Map[String, SecurityGroup]())((map, sg) => map + ((sg.getGroupId, sg)))
    val addresses = amazonEC2.describeAddresses.getAddresses
      .foldLeft(Map[String, Address]())((map, address) => map + ((address.getInstanceId, address)))
    val imageIdsAndVolumeIds = awsInstancesResult.foldLeft((List[String](), List[String]()))((list, awsInstance) => {
      val volumeids = awsInstance.getBlockDeviceMappings.map(mapping => mapping.getEbs.getVolumeId)
      (awsInstance.getImageId :: list._1, volumeids.toList ::: list._2)
    })
    val defaultSize = 200
    val imagesMap = getImageInformation(amazonEC2, imageIdsAndVolumeIds._1)
    val subList = imageIdsAndVolumeIds._2.subList(0, if (imageIdsAndVolumeIds._2.size > defaultSize) defaultSize else imageIdsAndVolumeIds._2.size).toList
    val volumesMap: Map[String, Volume] = getVolumeInfoMap(amazonEC2, subList)
    val snapshotsMap: Map[String, List[Snapshot]] = getSnapshotsMap(amazonEC2, subList)

    val instances: List[Instance] = awsInstancesResult.map {
      awsInstance =>
        val imageInfo = getImageInfo(awsInstance.getImageId, imagesMap)
        val instanceSecurityGroups = awsInstance.getSecurityGroups
        val securityGroupInfos = getSecurityGroupInfo(totalsecurityGroups, instanceSecurityGroups.toList)
        val instance = new Instance(None,
          Some(awsInstance.getInstanceId),
          awsInstance.getKeyName,
          Some(awsInstance.getState.toString),
          Option(awsInstance.getInstanceType),
          Option(awsInstance.getPlatform),
          Option(awsInstance.getArchitecture),
          Option(awsInstance.getPublicDnsName),
          Option(awsInstance.getLaunchTime.getTime),
          Some(StorageInfo(None, 0D, AWSInstanceType.toAWSInstanceType(awsInstance.getInstanceType).ramSize)),
          Some(StorageInfo(None, 0D, AWSInstanceType.toAWSInstanceType(awsInstance.getInstanceType).rootPartitionSize)),
          awsInstance.getTags.map(tag => KeyValueInfo(None, tag.getKey, tag.getValue)).toList,
          createSSHAccessInfo(awsInstance.getKeyName),
          List.empty,
          List.empty,
          Set.empty,
          Some(imageInfo),
          List.empty,
          Option(accountInfo),
          Option(awsInstance.getPlacement.getAvailabilityZone),
          Option(awsInstance.getPrivateDnsName),
          Option(awsInstance.getPrivateIpAddress),
          Option(awsInstance.getPublicIpAddress),
          addresses.get(awsInstance.getInstanceId).map(address => address.getPublicIp),
          Option(awsInstance.getMonitoring.getState),
          Option(awsInstance.getRootDeviceType),
          createBlockDeviceMapping(awsInstance.getBlockDeviceMappings.toList, volumesMap, snapshotsMap),
          securityGroupInfos,
          false,
          Option(accountInfo.regionName.get)
        )
        logger.debug(s"Instance OBJ:$instance")
        instance
    }
    instances
  }

  private def getAWSCredentials(builder: AWSContextBuilder): AWSCredentials = {
    new AWSCredentials() {
      def getAWSAccessKeyId: String = {
        builder.accessKey
      }

      def getAWSSecretKey: String = {
        builder.secretKey
      }
    }
  }

  def awsInstanceHelper(credentials: AWSCredentials, region: Region): AmazonEC2 = {
    val amazonEC2: AmazonEC2 = new AmazonEC2Client(credentials)
    amazonEC2.setRegion(region)
    amazonEC2
  }

  def getAWSInstances(amazonEC2: AmazonEC2): List[model.Instance] = {
    amazonEC2.describeInstances.getReservations.toList.flatMap {
      reservation => reservation.getInstances
    }
  }

  def getImageInformation(amazonEC2: AmazonEC2, imageIds: List[String]): Map[String, Image] = {
    val describeImagesRequest = new DescribeImagesRequest
    describeImagesRequest.setImageIds(imageIds)
    val imagesResult = amazonEC2.describeImages(describeImagesRequest)
    imagesResult.getImages.foldLeft(Map[String, Image]())((imageMap, image) => imageMap + ((image.getImageId, image)))
  }

  def getImageInfoMap(imagesMap: Map[String, Image]): Map[String, ImageInfo] = {
    imagesMap.foldLeft(Map[String, ImageInfo]())((imageInfoMap, image) => imageInfoMap + ((image._1, ImageInfo.fromNeo4jGraph(image._1.toLong).get)))
  }

  def getImageInfo(imageId: String, imagesMap: Map[String, Image]): ImageInfo = {
    logger.info(s"Image ID: $imageId")
    if (imageId.nonEmpty) {

      if (imagesMap.contains(imageId)) {
        val image = imagesMap(imageId)
        ImageInfo(None,
          Some(imageId),
          Option(image.getState),
          Option(image.getOwnerId),
          image.getPublic,
          Option(image.getArchitecture),
          Option(image.getImageType),
          Option(image.getPlatform),
          Option(image.getImageOwnerAlias),
          Option(image.getName),
          Option(image.getDescription),
          Option(image.getRootDeviceType),
          Option(image.getRootDeviceName),
          Some(""))
      } else {
        ImageInfo(None, Some(imageId), None, None, publicValue = false, None, None, None, None, None, None, None, None, None)
      }
    } else {
      ImageInfo(None, Some(imageId), None, None, publicValue = false, None, None, None, None, None, None, None, None, None)
    }
  }

  def createSSHAccessInfo(keyName: String): Option[SSHAccessInfo] = {
    val node = Neo4jRepository.getNodeByProperty("KeyPairInfo", "keyName", keyName)
    val keyPairInfo = node.flatMap { node => KeyPairInfo.fromNeo4jGraph(node.getId) }
    keyPairInfo.map(info => SSHAccessInfo(None, info, "", 0))
  }

  def getSecurityGroupInfo(totalSecurityGroups: Map[String, SecurityGroup], instanceGroupIdentifiers: List[GroupIdentifier]): List[SecurityGroupInfo] = {
    totalSecurityGroups.map {
      securityGroup =>
        if (totalSecurityGroups.contains(securityGroup._2.getGroupId)) {
          val sg = securityGroup._2
          val ipPermissions = sg.getIpPermissions.toList.map {
            ipPermission =>
              val fromPort = Option(ipPermission.getFromPort) match {
                case Some(port) => port.toInt
                case None => -1 // -1 means it will expose system level port
              }
              val toPort = Option(ipPermission.getToPort) match {
                case Some(port) => port.toInt
                case None => -1
              }
              IpPermissionInfo(None,
                fromPort,
                toPort,
                IpProtocol.toProtocol(ipPermission.getIpProtocol),
                ipPermission.getUserIdGroupPairs.map {
                  pair => pair.getGroupId
                }.toSet, ipPermission.getIpRanges.toList)
          }
          SecurityGroupInfo(None,
            Option(sg.getGroupName),
            Option(securityGroup._1),
            Option(sg.getOwnerId),
            Option(sg.getDescription),
            ipPermissions,
            sg.getTags.map(tag => KeyValueInfo(None, tag.getKey, tag.getValue)).toList
          )
        } else {
          SecurityGroupInfo.appply(None)
        }
    }.toList
  }

  def getComputeAPI(accountInfo: AccountInfo): AmazonEC2 = {
    val region = RegionUtils.getRegion(accountInfo.regionName.get)
    val aWSContextBuilder = AWSContextBuilder(accountInfo.accessKey.get, accountInfo.secretKey.get, accountInfo.regionName.get)
    val aWSCredentials1 = getAWSCredentials(aWSContextBuilder)
    awsInstanceHelper(aWSCredentials1, region)
  }

  def getReservedInstances(amazonEC2: AmazonEC2): List[ReservedInstanceDetails] = {
    amazonEC2.describeReservedInstances.getReservedInstances.foldLeft(List[ReservedInstanceDetails]()) {
      (list, reservedInstance) =>
        ReservedInstanceDetails(
          None,
          Option(reservedInstance.getInstanceType),
          Option(reservedInstance.getReservedInstancesId),
          Option(reservedInstance.getAvailabilityZone),
          Option(reservedInstance.getInstanceTenancy),
          Option(reservedInstance.getOfferingType),
          Option(reservedInstance.getProductDescription),
          Option(reservedInstance.getInstanceCount)
        ) :: list
    }
  }

  def getAutoScalingGroups(accountInfo: AccountInfo): List[ScalingGroup] = {
    val aWSRegion = RegionUtils.getRegion(accountInfo.regionName.get)
    val aWSContextBuilder: AWSContextBuilder = AWSContextBuilder(accountInfo.accessKey.get, accountInfo.secretKey.get, accountInfo.regionName.get)
    val aWSCredentials = getAWSCredentials(aWSContextBuilder)
    val amazonASG: AmazonAutoScalingClient = new AmazonAutoScalingClient(aWSCredentials)
    amazonASG.setRegion(aWSRegion)
    val asGroups = amazonASG.describeAutoScalingGroups().getAutoScalingGroups.toList
    createScalingGroups(asGroups)
  }

  def createScalingGroups(asGroups: List[AutoScalingGroup]): List[ScalingGroup] = {
    asGroups.map { asg =>
      val name = asg.getAutoScalingGroupName
      val status = Option(asg.getStatus)
      val launchConfigurationName = asg.getLaunchConfigurationName
      val availabilityZones = asg.getAvailabilityZones.toList
      val loadBalancerNames = asg.getLoadBalancerNames.toList
      val desiredCapacity = asg.getDesiredCapacity
      val maxCapacity = asg.getMaxSize
      val minCapacity = asg.getMinSize
      val instanceIds = asg.getInstances.map { awsInstance => awsInstance.getInstanceId }.toList
      val tags = asg.getTags.map { t => KeyValueInfo(None, t.getKey, t.getValue) }.toList
      ScalingGroup(None, name, launchConfigurationName, status, availabilityZones, instanceIds
        , loadBalancerNames, tags, desiredCapacity, maxCapacity, minCapacity)
    }
  }

  def getLoadBalancers(accountInfo: AccountInfo): List[LoadBalancer] = {
    val aWSRegion = RegionUtils.getRegion(accountInfo.regionName.get)
    val aWSContextBuilder: AWSContextBuilder = AWSContextBuilder(accountInfo.accessKey.get, accountInfo.secretKey.get, accountInfo.regionName.get)
    val aWSCredentials = getAWSCredentials(aWSContextBuilder)
    val amazonELB = new AmazonElasticLoadBalancingClient(aWSCredentials)
    amazonELB.setRegion(aWSRegion)
    val lbDescriptions = amazonELB.describeLoadBalancers().getLoadBalancerDescriptions.toList
    createLoadBalancer(lbDescriptions)
  }

  def createLoadBalancer(lbDescriptions: List[LoadBalancerDescription]): List[LoadBalancer] = {
    lbDescriptions.map { lbDesc =>
      val name = lbDesc.getLoadBalancerName
      val vpcId = Option(lbDesc.getVPCId)
      val availabilityZones = lbDesc.getAvailabilityZones.toList
      val instanceIds = lbDesc.getInstances.map { awsInstance => awsInstance.getInstanceId }.toList
      LoadBalancer(None, name, vpcId, None, instanceIds, availabilityZones)
    }
  }

  def createBlockDeviceMapping(blockDeviceMapping: List[InstanceBlockDeviceMapping]
                               , volumesMap: Map[String, Volume]
                               , snapshotsMap: Map[String
    , List[Snapshot]]): List[InstanceBlockDeviceMappingInfo] = {

    blockDeviceMapping.map { instanceBlockDeviceMapping =>
      val volumeId = instanceBlockDeviceMapping.getEbs.getVolumeId
      val volume: Volume = volumesMap(volumeId)
      val snapshots: List[Snapshot] = snapshotsMap.get(volumeId) match {
        case Some(snapShot) => snapshotsMap(volumeId)
        case None => List.empty[Snapshot]
      }
      val volumeInfo = createVolumeInfo(volume, snapshots)
      createInstanceBlockDeviceMappingInfo(instanceBlockDeviceMapping, volumeInfo, 0)
    }
  }

  def createInstanceBlockDeviceMappingInfo(instanceBlockDeviceMapping: InstanceBlockDeviceMapping
                                           , volumeInfo: VolumeInfo, usageInGB: Int): InstanceBlockDeviceMappingInfo = {
    val deviceName: String = instanceBlockDeviceMapping.getDeviceName
    val ebs: EbsInstanceBlockDevice = instanceBlockDeviceMapping.getEbs
    val status: String = ebs.getStatus
    val attachTime: String = ebs.getAttachTime.toString
    val deleteOnTermination: Boolean = ebs.getDeleteOnTermination
    InstanceBlockDeviceMappingInfo(None, deviceName, volumeInfo, status, attachTime, deleteOnTermination, usageInGB)
  }

  def createVolumeInfo(volume: Volume, snapshots: List[Snapshot]): VolumeInfo = {
    val volumeId = volume.getVolumeId
    val size: Int = volume.getSize
    val snapshotId = volume.getSnapshotId
    val availabilityZone = volume.getAvailabilityZone
    val state = volume.getState
    val createTime = volume.getCreateTime.toString
    val tags: List[KeyValueInfo] = createKeyValueInfo(volume.getTags.toList)
    val volumeType = volume.getVolumeType
    val snapshotCount = snapshots.size

    if (snapshots.nonEmpty) {
      val currentSnapshot = snapshots.reduceLeft { (current, next) =>
        if (next.getStartTime.compareTo(current.getStartTime) > 0 && next.getProgress.equals("100%")) {
          next
        }
        else {
          current
        }
      }
      val currentSnapshotInfo = createSnapshotInfo(currentSnapshot)

      VolumeInfo(None, Some(volumeId), Some(size), Some(snapshotId), Some(availabilityZone)
        , Some(state), Some(createTime), tags, Some(volumeType), Some(snapshotCount)
        , Some(currentSnapshotInfo))
    }
    else {
      VolumeInfo(None, Some(volumeId), Some(size), Some(snapshotId), Some(availabilityZone)
        , Some(state), Some(createTime), tags, Some(volumeType), Some(snapshotCount), None)
    }
  }

  def createSnapshotInfo(snapshot: Snapshot): SnapshotInfo = {
    val snapshotId = snapshot.getSnapshotId
    val volumeId = snapshot.getVolumeId
    val state = snapshot.getState
    val startTime = snapshot.getStartTime.toString
    val progress = snapshot.getProgress
    val ownerId = snapshot.getOwnerId
    val ownerAlias = snapshot.getOwnerAlias
    val description = snapshot.getDescription
    val volumeSize = snapshot.getVolumeSize
    val tags = createKeyValueInfo(snapshot.getTags.toList)
    SnapshotInfo(None, Some(snapshotId), Some(volumeId), Some(state), Some(startTime)
      , Some(progress), Some(ownerId), Some(ownerAlias), Some(description), Some(volumeSize), tags)
  }

  def createKeyValueInfo(tags: List[com.amazonaws.services.ec2.model.Tag]): List[KeyValueInfo] = {
    tags.map { tag =>
      val key = tag.getKey
      val value = tag.getValue
      KeyValueInfo(None, key, value)
    }
  }

  def getSnapshotsMap(amazonEC2: AmazonEC2, volumeIds: List[String]): Map[String, List[Snapshot]] = {
    if (volumeIds.nonEmpty) {
      val describeSnapshotsRequest: DescribeSnapshotsRequest = new DescribeSnapshotsRequest
      val filter = new com.amazonaws.services.ec2.model.Filter("volume-id", volumeIds)
      val describeSnapshotsResult: DescribeSnapshotsResult = amazonEC2.describeSnapshots(describeSnapshotsRequest
        .withFilters(filter))
      describeSnapshotsResult.getSnapshots.foldLeft(Map[String, List[Snapshot]]()) { (map, snapshot) =>
        val volumeId = snapshot.getVolumeId
        val list = map.get(volumeId)
        if (list.nonEmpty) {
          map + ((volumeId, snapshot :: list.get))
        } else {
          map + ((volumeId, List(snapshot)))
        }
      }
    } else {
      Map.empty[String, List[Snapshot]]
    }
  }

  def getVolumeInfoMap(amazonEC2: AmazonEC2, list: List[String]): Map[String, Volume] = {
    if (list.nonEmpty) {
      val describeVolumesRequest = new DescribeVolumesRequest()
      describeVolumesRequest.setVolumeIds(list)
      val describedVolumes = amazonEC2.describeVolumes(describeVolumesRequest)
      describedVolumes.getVolumes.foldLeft(Map.empty[String, Volume])((map, volume) => map + ((volume.getVolumeId, volume)))
    } else {
      Map.empty[String, Volume]
    }
  }
}
